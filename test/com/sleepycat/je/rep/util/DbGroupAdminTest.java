/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002,2010 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.JEVersion;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.log.LogEntryType;
import com.sleepycat.je.rep.MasterStateException;
import com.sleepycat.je.rep.MemberNotFoundException;
import com.sleepycat.je.rep.NodeState;
import com.sleepycat.je.rep.NodeType;
import com.sleepycat.je.rep.RepInternal;
import com.sleepycat.je.rep.ReplicaStateException;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.ReplicatedEnvironment.State;
import com.sleepycat.je.rep.ReplicationConfig;
import com.sleepycat.je.rep.ReplicationNode;
import com.sleepycat.je.rep.impl.RepGroupImpl;
import com.sleepycat.je.rep.impl.RepImpl;
import com.sleepycat.je.rep.impl.RepNodeImpl;
import com.sleepycat.je.rep.impl.node.MasterTransferTest;
import com.sleepycat.je.rep.impl.node.NameIdPair;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.utilint.LoggerUtils;
import com.sleepycat.je.utilint.VLSN;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/*
 * A unit test which tests the DbGroupAdmin utility and also the utilities
 * provided by ReplicationGroupAdmin.
 */
public class DbGroupAdminTest extends TestBase {
    private final File envRoot;
    private RepEnvInfo[] repEnvInfo;

    public DbGroupAdminTest() {
        envRoot = SharedTestUtils.getTestDir();
    }

    @Override
    @Before
    public void setUp()
        throws Exception {

        super.setUp();
        repEnvInfo = RepTestUtils.setupEnvInfos(envRoot, 3);
    }

    @Override
    @After
    public void tearDown() {
        RepTestUtils.shutdownRepEnvs(repEnvInfo);
    }

    /*
     * Test the removeMember behavior of DbGroupAdmin, since DbGroupAdmin
     * invokes ReplicationGroupAdmin, so it tests ReplicationGroupAdmin too.
     *
     * TODO: When the simple majority is configurable, need to test that a 
     * group becomes electable again when some nodes are removed.
     */
    @Test
    public void testRemoveMember()
        throws Exception {

        ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);

        /* Construct a DbGroupAdmin instance. */
        DbGroupAdmin dbAdmin = 
            new DbGroupAdmin(RepTestUtils.TEST_REP_GROUP_NAME, 
                             master.getRepConfig().getHelperSockets());

        /* Removing the master will result in MasterStateException. */
        try {
            dbAdmin.removeMember(master.getNodeName());
            fail("Shouldn't execute here, expect an exception.");
        } catch (MasterStateException e) {
            /* Expected exception. */
        }

        /* 
         * Removing a non-existent node will result in 
         * MemberNotFoundException. 
         */
        try {
            dbAdmin.removeMember("Node 5");
            fail("Removing a non-existent node should fail.");
        } catch (MemberNotFoundException e) {
            /* Expected exception. */
        }

        /* Remove Node 3 from the group using the removeMember API. */
        try {
            dbAdmin.removeMember(repEnvInfo[2].getEnv().getNodeName());
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
        }
        RepGroupImpl groupImpl = master.getGroup().getRepGroupImpl();
        assertEquals(groupImpl.getAllElectableMembers().size(), 2);
       
        /* Remove Node 2 from the group using main method. */
        try {
            String[] args = new String[] {
                "-groupName", RepTestUtils.TEST_REP_GROUP_NAME, 
                "-helperHosts", master.getRepConfig().getNodeHostPort(),
                "-removeMember", repEnvInfo[1].getEnv().getNodeName() };
            DbGroupAdmin.main(args);
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
        }
        groupImpl = master.getGroup().getRepGroupImpl();
        assertEquals(groupImpl.getAllElectableMembers().size(), 1);
    }

    /*
     * Test that mastership transfer behavior of DbGroupAdmin.
     * @see com.sleepycat.je.rep.impl.node.MasterTransferTest
     */
    @Test
    public void testMastershipTransfer()
        throws Exception {

        ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);
        assertTrue(master.getState().isMaster());

        /* Construct a DbGroupAdmin instance. */
        DbGroupAdmin dbAdmin =
            new DbGroupAdmin(RepTestUtils.TEST_REP_GROUP_NAME,
                             master.getRepConfig().getHelperSockets());

        /* Transfer master to a nonexistent replica. */
        try {
            dbAdmin.transferMaster("node 5", "5 s");
            fail("Shouldn't execute here, expect an exception");
        } catch (MemberNotFoundException e) {
            /* Expected exception. */
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
        }
        assertTrue(master.getState().isMaster());

        /* Transfer master to a monitor */
        final ReplicationGroupAdmin repGroupAdmin =
            new ReplicationGroupAdmin(RepTestUtils.TEST_REP_GROUP_NAME,
                                      master.getRepConfig().getHelperSockets());
        final RepImpl lastImpl =
            RepInternal.getRepImpl(repEnvInfo[repEnvInfo.length-1].getEnv());
        final int lastId = lastImpl.getNodeId();
        final short monitorId = (short)(lastId+1);
        final RepNodeImpl monitorNode =
            new RepNodeImpl(new NameIdPair("monitor" + monitorId, monitorId),
                            NodeType.MONITOR,
                            lastImpl.getHostName(),
                            lastImpl.getPort()+1);
        repGroupAdmin.ensureMonitor(monitorNode);
        try {
            dbAdmin.transferMaster(monitorNode.getName(), "1 s");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            /* Expected exception */
        } catch (Exception e) {
            /* Unexpected exception */
            throw e;
        }

        /* Transfer the mastership to node 1. */
        PrintStream original = System.out;
        try {
            /* Avoid polluting the test output. */
            System.setOut(new PrintStream(new ByteArrayOutputStream()));
            dbAdmin.transferMaster(repEnvInfo[1].getEnv().getNodeName(), 
                                   "5 s");
            MasterTransferTest.awaitSettle(repEnvInfo[0], repEnvInfo[1]);
        } catch (Exception e) {
            fail("Unexpected exception: " + LoggerUtils.getStackTrace(e));
        } finally {
            System.setOut(original);
        }

        /* Check the node state. */
        assertTrue(repEnvInfo[0].isReplica());
        assertTrue(repEnvInfo[1].isMaster());

        /* Do some database operations to make sure everything is OK. */
        final String dbName = "testDB";
        final String dataString = "herococo";
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setTransactional(true);
        Database db = 
            repEnvInfo[1].getEnv().openDatabase(null, dbName , dbConfig);

        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        for (int i = 1; i <= 50; i++) {
            IntegerBinding.intToEntry(i, key);
            StringBinding.stringToEntry(dataString, data);
            assertTrue(OperationStatus.SUCCESS == db.put(null, key, data));
        }
        db.close();

        RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);

        /* Check the old master is OK. */
        db = repEnvInfo[0].getEnv().openDatabase(null, dbName, dbConfig);
        for (int i = 1; i <= 50; i++) {
            IntegerBinding.intToEntry(i, key);
            assertTrue
                (OperationStatus.SUCCESS == db.get(null, key, data, null));
            assertTrue(StringBinding.entryToString(data).equals(dataString));
        }
        db.close();
    }

    /*
     * Test the update address utility of DbGroupAdmin. */
    @Test
    public void testUpdateAddress()
        throws Exception {

        ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);
        assertTrue(master.getState().isMaster());

        /* Construct a DbGroupAdmin instance. */
        DbGroupAdmin dbAdmin =
            new DbGroupAdmin(RepTestUtils.TEST_REP_GROUP_NAME,
                             master.getRepConfig().getHelperSockets());

        try {
            dbAdmin.updateAddress("node 5", "localhost", 5004);
            fail("Shouldn't execute here, expect an exception.");
        } catch (MemberNotFoundException e) {
            /* Expected exceptions. */
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
        }
        assertTrue(master.getState().isMaster());

        try {
            dbAdmin.updateAddress(master.getNodeName(), "localhost", 5004);
            fail("Shouldn't execute here, expect an exception.");
        } catch (MasterStateException e) {
            /* Expected exception. */
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
        }
        assertTrue(master.getState().isMaster());

        final String nodeName = repEnvInfo[1].getEnv().getNodeName();
        final File envHome = repEnvInfo[1].getEnvHome();
        final EnvironmentConfig envConfig = repEnvInfo[1].getEnvConfig();
        final ReplicationConfig repConfig = repEnvInfo[1].getRepConfig();
        try {
            dbAdmin.updateAddress(nodeName, "localhost", 5004);
            fail("Shouldn't execute here, expect an exception.");
        } catch (ReplicaStateException e) {
            /* Expected exception. */
        } catch (Exception e) {
            fail("Unexpected exceptoin: " + e);
        }
        assertTrue(master.getState().isMaster());
        assertTrue(repEnvInfo[1].isReplica());

        /* Shutdown the second replica. */
        repEnvInfo[1].closeEnv();
        try {
            dbAdmin.updateAddress(nodeName, "localhost", 5004);
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
        }

        /* Reopen the repEnvInfo[1] with updated configurations. */
        repConfig.setNodeHostPort("localhost:5004");
        ReplicatedEnvironment replica = null;
        try {
            replica = new ReplicatedEnvironment(envHome, repConfig, envConfig);
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
        }
        assertTrue(replica.getState().isReplica());
        assertEquals(replica.getRepConfig().getNodeHostname(), 
                     "localhost");
        assertEquals(replica.getRepConfig().getNodePort(), 5004);
        replica.close();
    }

    /* Test behaviors of ReplicationGroupAdmin. */
    @Test
    public void testReplicationGroupAdmin() 
        throws Exception {

        ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);

        ReplicationGroupAdmin groupAdmin = new ReplicationGroupAdmin
            (RepTestUtils.TEST_REP_GROUP_NAME,
             master.getRepConfig().getHelperSockets());

        /* Test the DbPing utility. */
        RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);
        VLSN commitVLSN = repEnvInfo[0].getRepNode().getCurrentTxnEndVLSN();
        String groupName = groupAdmin.getGroupName(); 
        String masterName = groupAdmin.getMasterNodeName();

        Set<ReplicationNode> replicationNodes = 
            groupAdmin.getGroup().getElectableNodes();
        for (ReplicationNode replicationNode : replicationNodes) {
            NodeState nodeState = 
                groupAdmin.getNodeState(replicationNode, 10000);
            assertEquals(nodeState.getGroupName(), groupName);
            assertEquals(nodeState.getMasterName(), masterName);
            assertEquals(nodeState.getJEVersion(), JEVersion.CURRENT_VERSION);
            /* Check the values depends on the node state. */
            if (replicationNode.getName().equals(masterName)) {
                assertEquals(nodeState.getNodeState(), State.MASTER);
                assertEquals(nodeState.getActiveFeeders(), 
                             repEnvInfo.length - 1);
                assertEquals(nodeState.getKnownMasterTxnEndVLSN(), 0);
            } else {
                assertEquals(nodeState.getNodeState(), State.REPLICA);
                assertEquals(nodeState.getActiveFeeders(), 0);
                assertEquals(nodeState.getKnownMasterTxnEndVLSN(),
                             commitVLSN.getSequence());
            }
            assertEquals(nodeState.getCurrentTxnEndVLSN(),
                         commitVLSN.getSequence());
            assertEquals(nodeState.getLogVersion(), LogEntryType.LOG_VERSION);
        }
        
        /* Check the master name. */
        assertEquals(master.getNodeName(), groupAdmin.getMasterNodeName());

        /* Check the group information. */
        RepGroupImpl repGroupImpl = master.getGroup().getRepGroupImpl();
        assertEquals(repGroupImpl, groupAdmin.getGroup().getRepGroupImpl());

        /* Check the ensureMember utility, no monitors at the begining. */
        assertEquals(repGroupImpl.getMonitorNodes().size(), 0);

        ReplicationConfig monitorConfig = new ReplicationConfig();
        monitorConfig.setNodeName("Monitor1");
        monitorConfig.setGroupName(RepTestUtils.TEST_REP_GROUP_NAME);
        monitorConfig.setNodeHostPort(RepTestUtils.TEST_HOST + ":" + "5004");
        monitorConfig.setHelperHosts(master.getRepConfig().getNodeHostPort());

        /* Add a new monitor. */
        RepNodeImpl monitorNode = 
            new RepNodeImpl(new NameIdPair(monitorConfig.getNodeName()),
                            NodeType.MONITOR,
                            monitorConfig.getNodeHostname(),
                            monitorConfig.getNodePort());
        groupAdmin.ensureMonitor(monitorNode);

        /* Check the group information and monitor after insertion. */
        repGroupImpl = master.getGroup().getRepGroupImpl();
        assertEquals(repGroupImpl, groupAdmin.getGroup().getRepGroupImpl());
        assertEquals(repGroupImpl.getMonitorNodes().size(), 1);
    }
}
