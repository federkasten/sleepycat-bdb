/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.rep.InsufficientLogException;
import com.sleepycat.je.rep.NetworkRestore;
import com.sleepycat.je.rep.NetworkRestoreConfig;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.ReplicationConfig;
import com.sleepycat.je.rep.impl.RepTestBase;
import com.sleepycat.je.rep.impl.node.FeederManager;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.util.TestUtils;

/**
 * Tests operation of the DbResetGroup utility
 */
public class ResetRepGroupTest extends RepTestBase {

    /* Prefix associated with new nodes in the reset rep group. */
    private static final String NEW_NODE_PREFIX = "nx";

    /* The new "reset" rep group name. */
    private static final String NEW_GROUP_NAME = "gx";

    @Before
    public void setUp() 
        throws Exception {
        
        groupSize = 3;
        super.setUp();
    }

    @Test
    public void testBasic() 
        throws InterruptedException {

        for (RepEnvInfo info : repEnvInfo) {
            EnvironmentConfig config = info.getEnvConfig();
            /* Smaller log files to provoke faster cleaning */
            config.setConfigParam(EnvironmentConfig.LOG_FILE_MAX, "100000");
        }

        createGroup();
        populateDB(repEnvInfo[0].getEnv(), "db1", 10000);
        closeNodes(repEnvInfo);

        ReplicationConfig config0 = repEnvInfo[0].getRepConfig();

        resetRepEnvInfo(config0);

        DbResetRepGroup reset = new DbResetRepGroup(repEnvInfo[0].getEnvHome(),
                                                    config0.getGroupName(),
                                                    config0.getNodeName(),
                                                    config0.getNodeHostPort());
        reset.reset();

        /*
         * Open the environment that was converted, to the new group, node and
         * hostport location
         */
        ReplicatedEnvironment env0 = repEnvInfo[0].openEnv();

        assertEquals(NEW_GROUP_NAME, env0.getGroup().getName());
        assertTrue(env0.getNodeName().startsWith(NEW_NODE_PREFIX));

        /* Check that new internal node id were allocated. */
        assertTrue(repEnvInfo[0].getRepNode().getNodeId() > repEnvInfo.length);

        /*
         * Create enough data to provoke cleaning and truncation of the VLSN
         * index.
         */
        populateDB(env0, "db1", 1000);

        /* Create cleaner fodder */
        env0.removeDatabase(null, "db1");

        /*
         * Wait for Master VLSN update.  Because there are no replicas,
         * FeederManager.runFeeders must wait for the call to
         * BlockingQueue.poll timeout, before updating the master's CBVLSN.
         */
        Thread.sleep(FeederManager.MASTER_CHANGE_CHECK_TIMEOUT * 2);

        env0.cleanLog();
        env0.checkpoint(new CheckpointConfig().setForce(true));
        assertTrue ("failed to provoke cleaning",
                    env0.getStats(null).getNCleanerDeletions() > 0);

        /* Grow the group expecting ILEs when opening the environment. */
        for (int i=1; i < repEnvInfo.length; i++) {
            try {
                repEnvInfo[i].openEnv();
                fail("ILE exception expected");
            } catch (InsufficientLogException ile) {
                NetworkRestore restore = new NetworkRestore();
                restore.execute(ile, new NetworkRestoreConfig());
                ReplicatedEnvironment env = repEnvInfo[i].openEnv();

                assertEquals(NEW_GROUP_NAME, env.getGroup().getName());
                assertTrue(env.getNodeName().startsWith(NEW_NODE_PREFIX));
                assertTrue(repEnvInfo[i].getRepNode().getNodeId() >
                           repEnvInfo.length);
            }
        }
    }

    /**
     * Updates the repEnvInfo array with the configuration for the new
     * singleton group. It also removes all environment directories for all
     * nodes with the exception of the first.
     */
    private void resetRepEnvInfo(ReplicationConfig config0)
        throws IllegalArgumentException {
        /* Assign new group, name and ports for the nodes. */
        for (int i = 0; i < repEnvInfo.length; i++) {
            RepEnvInfo info = repEnvInfo[i];
            ReplicationConfig rconfig = info.getRepConfig();
            int newPort = rconfig.getNodePort() + repEnvInfo.length;
            rconfig.setGroupName(NEW_GROUP_NAME);
            rconfig.setNodeName(NEW_NODE_PREFIX + i);
            rconfig.setNodeHostPort(rconfig.getNodeHostname() + ":" + newPort);
            rconfig.setHelperHosts(config0.getNodeHostPort());

            EnvironmentConfig econfig = info.getEnvConfig();
            /*
             * Turn off the cleaner, since it's controlled explicitly
             * by the test.
             */
            econfig.setConfigParam(EnvironmentConfig.ENV_RUN_CLEANER,
                                   "false");

            /* Remove all other environment directories. */
            if (i > 0) {
                TestUtils.removeLogFiles("RemoveRepEnvironments",
                                         info.getEnvHome(),
                                         false); // checkRemove
            }
        }
    }
}
