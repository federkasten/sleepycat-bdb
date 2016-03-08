package com.sleepycat.je.rep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import com.sleepycat.je.CommitToken;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.je.rep.impl.RepTestBase;

public class CommitPointConsistencyPolicyTest extends RepTestBase {

    @Override
    @Before
    public void setUp()
        throws Exception {

        groupSize = 2;
        super.setUp();
    }

    @Test
    public void testCommitPointConsistencyOnOpen() {
        ReplicatedEnvironment menv = repEnvInfo[0].openEnv();
        CommitToken token = populateDB(menv, TEST_DB_NAME, 10);
        CommitPointConsistencyPolicy cp =
            new CommitPointConsistencyPolicy(token, 100, TimeUnit.SECONDS);
        ReplicatedEnvironment renv = repEnvInfo[1].openEnv(cp);
        /* Verify that the database is available on the replica. */
        Database rdb = renv.openDatabase(null, TEST_DB_NAME, dbconfig);
        rdb.close();
    }

    @Test
    public void testVLSNConsistencyJoinGroup()
        throws UnknownMasterException,
               DatabaseException,
               InterruptedException {

        createGroup();
        leaveGroupAllButMaster();
        ReplicatedEnvironment masterRep = repEnvInfo[0].getEnv();
        /* Populate just the master. */
        CommitToken commitToken = populateDB(masterRep, TEST_DB_NAME, 100);

        ReplicatedEnvironment replica = repEnvInfo[1].openEnv();
        Environment env = replica;
        TransactionConfig tc = new TransactionConfig();
        CommitPointConsistencyPolicy cp1 =
            new CommitPointConsistencyPolicy(commitToken, 1, TimeUnit.SECONDS);
        tc.setConsistencyPolicy(cp1);

        // In sync to the commit point
        Transaction txn = env.beginTransaction(null, tc);
        txn.commit();

        int timeout = 2000;
        CommitToken futureCommitToken =
            new CommitToken(commitToken.getRepenvUUID(),
                            commitToken.getVLSN() + 100);

        CommitPointConsistencyPolicy cp2 =
            new CommitPointConsistencyPolicy(futureCommitToken, timeout,
                                             TimeUnit.MILLISECONDS);

        tc.setConsistencyPolicy(cp2);
        long start = System.currentTimeMillis();
        try {
            txn = null;
            // Unable to reach consistency, timeout.
            txn = env.beginTransaction(null, tc);
            txn.abort();
            fail("Exception expected");
        } catch (ReplicaConsistencyException rce) {
            long policyTimeout =
                rce.getConsistencyPolicy().getTimeout(TimeUnit.MILLISECONDS);
            assertTrue(policyTimeout <= (System.currentTimeMillis() - start));
        }

        // reset statistics
        StatsConfig statsConf = new StatsConfig();
        replica.getRepStats(statsConf.setClear(true));

        // Have a replica transaction actually wait
        TxnThread txnThread = new TxnThread(replica, tc);
        txnThread.start();
        Thread.yield(); // give the other threads a chance to block
        // Advance the master
        populateDB(masterRep, TEST_DB_NAME, 100, 100);

        txnThread.join(timeout);
        assertTrue(!txnThread.isAlive());
        assertNull(txnThread.testException);
        ReplicatedEnvironmentStats stats =
            replica.getRepStats(statsConf.setClear(true));
        assertEquals(1, stats.getTrackerVLSNConsistencyWaits());

        // Test with a commit token which is in the past replica does not need
        // to wait.

        tc.setConsistencyPolicy(cp1);
        txn = env.beginTransaction(null, tc);
        stats = replica.getRepStats(statsConf.setClear(true));
        assertEquals(0, stats.getTrackerVLSNConsistencyWaits());
        txn.commit();
    }

    class TxnThread extends Thread {
        final ReplicatedEnvironment replicator;
        final TransactionConfig tc;
        Exception testException = null;

        TxnThread(ReplicatedEnvironment replicator, TransactionConfig tc) {
            this.replicator = replicator;
            this.tc = tc;
        }

        @Override
        public void run() {
            try {
                Transaction txn = replicator.beginTransaction(null, tc);
                txn.commit();
            } catch (Exception e) {
                testException = e;
            }
        }
    }
}
