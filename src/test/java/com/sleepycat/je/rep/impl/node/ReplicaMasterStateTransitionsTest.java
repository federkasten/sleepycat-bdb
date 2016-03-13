/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.impl.node;

import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.sleepycat.je.CommitToken;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.je.rep.CommitPointConsistencyPolicy;
import com.sleepycat.je.rep.RepInternal;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.StateChangeEvent;
import com.sleepycat.je.rep.StateChangeListener;
import com.sleepycat.je.rep.impl.RepTestBase;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.util.FileHandler;

public class ReplicaMasterStateTransitionsTest extends RepTestBase {

    /*
     * This test was motivated by SR 18212. In this test node 1 starts out as a
     * master, relinquishes mastership to node 2, and then tries to resume as a
     * replica with node 2 as the master.
     */
    @Test
    public void testMasterReplicaTransition()
        throws Throwable {

        FileHandler.STIFLE_DEFAULT_ERROR_MANAGER = true;
        createGroup();
        ReplicatedEnvironment renv1 = repEnvInfo[0].getEnv();
        assertTrue(renv1.getState().isMaster());
        {
            Transaction txn =
                renv1.beginTransaction(null, RepTestUtils.SYNC_SYNC_ALL_TC);
            renv1.openDatabase(txn, "db1", dbconfig).close();
            txn.commit();
        }
        final ReplicatedEnvironment renv2 = repEnvInfo[1].getEnv();
        final CountDownLatch master2Latch = new CountDownLatch(1);
        renv2.setStateChangeListener(new StateChangeListener() {
            @Override
            public void stateChange(StateChangeEvent stateChangeEvent)
                throws RuntimeException {
               if (stateChangeEvent.getState().isMaster()) {
                   master2Latch.countDown();
               }
            }
        });

        final RepNode rn2 =  RepInternal.getRepImpl(renv2).getRepNode();
        rn2.forceMaster(true);

        /* Wait for elections and node state transitions to subside. */
        assertTrue(master2Latch.await(60, TimeUnit.SECONDS));
        renv1 = repEnvInfo[0].getEnv();

        CommitToken db2CommitToken = null;
        {
            Transaction txn =
                renv2.beginTransaction(null, RepTestUtils.SYNC_SYNC_ALL_TC);
            renv2.openDatabase(txn, "db2", dbconfig).close();
            txn.commit();
            db2CommitToken = txn.getCommitToken();
        }

        /*
         * Verify that the change was replayed at the replica via the
         * replication stream.
         */
        {
            TransactionConfig txnConfig = new TransactionConfig();
            txnConfig.setConsistencyPolicy
                (new CommitPointConsistencyPolicy
                 (db2CommitToken, 60, TimeUnit.SECONDS));
            Transaction txn = renv1.beginTransaction(null, txnConfig);
            assertTrue(renv1.getDatabaseNames().contains("db2"));
            txn.commit();
        }
    }
}
