/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */
package com.sleepycat.je.rep.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.je.dbi.EnvironmentFailureReason;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.rep.InsufficientAcksException;
import com.sleepycat.je.rep.MasterStateException;
import com.sleepycat.je.rep.MemberNotFoundException;
import com.sleepycat.je.rep.RepInternal;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.ReplicatedEnvironment.State;
import com.sleepycat.je.rep.ReplicationConfig;
import com.sleepycat.je.rep.UnknownMasterException;
import com.sleepycat.je.rep.impl.node.NameIdPair;
import com.sleepycat.je.rep.impl.node.RepNode;
import com.sleepycat.je.rep.txn.MasterTxn;
import com.sleepycat.je.rep.txn.MasterTxn.MasterTxnFactory;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.util.TestUtils;

public class DynamicGroupTest extends RepTestBase {

    @Before
    public void setUp() 
        throws Exception {
        
        groupSize = 5;
        super.setUp();
    }

    @Test
    public void testRemoveMemberExceptions() {
        createGroup(2);
        ReplicatedEnvironment master = repEnvInfo[0].getEnv();
        assertTrue(master.getState().isMaster());

        RepNode masterRep = repEnvInfo[0].getRepNode();
        try {
            masterRep.removeMember(master.getNodeName());
            fail("Exception expected.");
        } catch (MasterStateException e) {
            // Expected
        }

        try {
            masterRep.removeMember("unknow node foobar");
            fail("Exception expected.");
        } catch (MemberNotFoundException e) {
            // Expected
        }

        masterRep.removeMember(repEnvInfo[1].getRepNode().getNodeName());
        try {
            masterRep.removeMember(repEnvInfo[1].getRepNode().getNodeName());
            fail("Exception expected.");
        } catch (MemberNotFoundException e) {
            // Expected
        }
        repEnvInfo[1].closeEnv();
    }

    /*
     * Tests internal node deletion APIs.
     */
    @Test
    public void testRemoveMember() {
        createGroup(groupSize);
        ReplicatedEnvironment master = repEnvInfo[0].getEnv();
        assertTrue(master.getState().isMaster());

        RepNode masterRep = repEnvInfo[0].getRepNode();

        /* Reduce the group size all the way down to one. */
        for (int i = 1; i < groupSize;  i++) {
            assertTrue(!RepInternal.isClosed(repEnvInfo[i].getEnv()));
            masterRep.removeMember(repEnvInfo[i].getEnv().getNodeName());
            assertEquals((groupSize-i), masterRep.getGroup().getElectableGroupSize());
        }

        /* Close the replica handles*/
        for (int i = groupSize-1; i > 0;  i--) {
            repEnvInfo[i].closeEnv();
        }

        /* Attempting to re-open them with the same node names should fail. */
        for (int i = 1; i < groupSize;  i++) {
            try {
                repEnvInfo[i].openEnv();
                fail("Exception expected");
            } catch (EnvironmentFailureException e) {
                /* Expected, the master should reject the attempt. */
                assertEquals(EnvironmentFailureReason.HANDSHAKE_ERROR,
                             e.getReason());
            }
        }

        /* Doing the same but with different node names should be ok. */
        for (int i = 1; i < groupSize;  i++) {
            final RepEnvInfo ri = repEnvInfo[i];
            final ReplicationConfig repConfig = ri.getRepConfig();
            TestUtils.removeLogFiles("RemoveRepEnvironments",
                                     ri.getEnvHome(),
                                     false);

            repConfig.setNodeName("ReplaceNode_" + i);
            ri.openEnv();
            assertEquals(i+1, masterRep.getGroup().getElectableGroupSize());
        }
        master.close();
    }

    /*
     * Verifies that an InsufficientAcksException is not thrown if the group
     * size changes while a transaction commit is waiting for acknowledgments.
     */
    @Test
    public void testMemberDeleteAckInteraction() {
        createGroup(groupSize);
        Transaction txn = null;
        Database db = null;
        try {
            MasterTxn.setFactory(new TxnFactory());
            ReplicatedEnvironment master = repEnvInfo[0].getEnv();

            txn = master.beginTransaction(null, null);
            /* Write to the environment. */
            db = master.openDatabase(txn, "random", dbconfig);
            db.close();
            txn.commit();
        } catch (InsufficientAcksException e) {
            fail ("No exception expected.");
        } finally {
            MasterTxn.setFactory(null);
        }
    }

    @Test
    public void testNoQuorum()
        throws UnknownMasterException,
               DatabaseException,
               InterruptedException {

        for (int i=0; i < 3; i++) {
            ReplicatedEnvironment rep = repEnvInfo[i].openEnv();
            State state = rep.getState();
            assertEquals((i == 0) ? State.MASTER : State.REPLICA, state);
        }
        RepTestUtils.syncGroupToLastCommit(repEnvInfo, 3);
        repEnvInfo[1].closeEnv();
        repEnvInfo[2].closeEnv();

        // A new node joining in the absence of a quorum must fail
        try {
            repEnvInfo[3].openEnv();
            fail("Expected exception");
        } catch (EnvironmentFailureException e) {
            assertEquals(EnvironmentFailureReason.HANDSHAKE_ERROR,
                         e.getReason());
            /* Expected. */
        }
    }

    /* Start the master (the helper node) first */
    @Test
    public void testGroupCreateMasterFirst()
        throws DatabaseException {

        for (int i=0; i < repEnvInfo.length; i++) {
            ReplicatedEnvironment rep = repEnvInfo[i].openEnv();
            State state = rep.getState();
            assertEquals((i == 0) ? State.MASTER : State.REPLICA, state);
            RepNode repNode = RepInternal.getRepImpl(rep).getRepNode();
            /* No elections, helper nodes or members queried for master. */
            assertEquals(0, repNode.getElections().getElectionCount());
        }
    }

    /*
     * Start the master (the helper node) last, so the other nodes have to
     * wait and retry until the helper node comes up.
     */
    @Test
    public void testGroupCreateMasterLast()
        throws DatabaseException,
           InterruptedException {

        RepNodeThread threads[] = new RepNodeThread[repEnvInfo.length];

        /* Start up non-masters, they should wait */
        for (int i=1; i < repEnvInfo.length; i++) {
            threads[i]=new RepNodeThread(i);
            threads[i].start();
        }

        State state = repEnvInfo[0].openEnv().getState();
        assertEquals(State.MASTER, state);

        for (int i=1; i < repEnvInfo.length; i++) {
            threads[i].join(30000);
            assertTrue(!threads[i].isAlive());
            assertNull(threads[i].te);
        }
    }

    class RepNodeThread extends Thread {
        final int id;
        ReplicatedEnvironment.State state;
        Throwable te;

        RepNodeThread(int id) {
            this.id = id;
        }

        @Override
        public void run() {

            try {
                state = repEnvInfo[id].openEnv().getState();
            } catch (Throwable e) {
                te = e;
                te.printStackTrace();
            }
        }
    }

    /*
     * Factory for producing test MasterTxns
     */
    private class TxnFactory implements MasterTxnFactory {
        final Thread thread = Thread.currentThread();

        public MasterTxn create(EnvironmentImpl envImpl,
                                TransactionConfig config,
                                NameIdPair nameIdPair) {
            if (Thread.currentThread() != thread) {
                return new MasterTxn(envImpl, config, nameIdPair);
            }
            return new TestMasterTxn(envImpl, config, nameIdPair);
        }
    }

    private class TestMasterTxn extends MasterTxn {

        public TestMasterTxn(EnvironmentImpl envImpl,
                             TransactionConfig config,
                             NameIdPair nameIdPair)
            throws DatabaseException {

            super(envImpl, config, nameIdPair);
        }

        @Override
        protected void preLogCommitHook() {
            super.preLogCommitHook();
            RepNode rmMasterNode = repEnvInfo[0].getRepNode();
            int size = rmMasterNode.getGroup().getAllElectableMembers().size();
            int delNodes = ((size & 1) == 1) ? 2 : 1;
            int closeNodeIndex = (size - delNodes) - 1;

            /*
             * The loop below simulates the concurrent removal of a node while
             * a transaction is in progress. It deletes a sufficient number of
             * nodes so as to get a lower simple nodes to get to a new lower
             * simple majority.
             */
            for (int i= repEnvInfo.length-1; delNodes-- > 0; i--) {
                repEnvInfo[i].closeEnv();
                rmMasterNode.removeMember(repEnvInfo[i].getRepConfig().
                                          getNodeName());
            }

            /*
             * Shut down an additional undeleted Replica to provoke a
             * lack of acks based on the old simple majority.
             */
            repEnvInfo[closeNodeIndex].closeEnv();
        }
    }
}
