/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */
package com.sleepycat.je.rep.impl.node;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.logging.Logger;

import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Durability;
import com.sleepycat.je.Durability.ReplicaAckPolicy;
import com.sleepycat.je.Durability.SyncPolicy;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentStats;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.je.rep.RepInternal;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.ReplicationConfig;
import com.sleepycat.je.rep.impl.RepParams;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.rep.vlsn.VLSNIndex;
import com.sleepycat.je.rep.vlsn.VLSNRange;
import com.sleepycat.je.utilint.LoggerUtils;
import com.sleepycat.je.utilint.VLSN;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/**
 * Test that the cleaner will is throttled by the CBVLSN.
 */
public class CleanerThrottleTest extends TestBase {

    private final int heartbeatMS = 500;

    /* Replication tests use multiple environments. */
    private final File envRoot;
    private final Logger logger;
    private final StatsConfig statsConfig;

    public CleanerThrottleTest() {
        envRoot = SharedTestUtils.getTestDir();
        logger = LoggerUtils.getLoggerFixedPrefix(getClass(), "Test");
        statsConfig = new StatsConfig();
        statsConfig.setClear(true);
    }

    /**
     * All nodes running, should clean a lot.
     */
    @Test
    public void testAllNodesClean()
        throws Throwable {

        runAndClean(false); // killOneNode
    }

    /**
     * One node from a three node group is killed off, should hold the
     * replication stream steady.
     */
    @Test
    public void testTwoNodesClean()
        throws Throwable {

        runAndClean(true); // killOneNode
    }

    /**
     * Create 3 nodes and replicate operations.
     * Kill off the master, and make the other two resume. This will require
     * a syncup and a rollback of any operations after the matchpoint.
     */
    public void runAndClean(boolean killOneNode)
        throws Throwable {

        RepEnvInfo[] repEnvInfo = null;

        EnvironmentConfig smallFileConfig = new EnvironmentConfig();
        DbInternal.disableParameterValidation(smallFileConfig);
        /* Use uniformly small log files, to permit cleaning.  */
        smallFileConfig.setConfigParam(EnvironmentConfig.LOG_FILE_MAX, "2000");
        smallFileConfig.setAllowCreate(true);
        smallFileConfig.setTransactional(true);
        /* Turn off the cleaner so we can call cleanLog explicitly. */
        smallFileConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CLEANER,
                                       "false");
        smallFileConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CHECKPOINTER,
                                       "false");
        ReplicationConfig repConfig = new ReplicationConfig();
        RepInternal.disableParameterValidation(repConfig);
        repConfig.setConfigParam(RepParams.HEARTBEAT_INTERVAL.getName(),
                                 (new Integer(heartbeatMS)).toString());
        repConfig.setConfigParam
            (RepParams.MIN_RETAINED_VLSNS.getName(), "10");

        try {
            /* Create a 3 node group */
            repEnvInfo = RepTestUtils.setupEnvInfos(envRoot,
                                                    3,
                                                    smallFileConfig,
                                                    repConfig);
            ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);
            VLSNIndex vlsnIndex =
                RepInternal.getRepImpl(master).getVLSNIndex();
            VLSN lastVLSNBeforeKill = vlsnIndex.getRange().getLast();
            logger.info("VLSN before env is closed: " + lastVLSNBeforeKill);
            if (killOneNode) {
                for (RepEnvInfo repi : repEnvInfo) {
                    if (repi.getEnv() != master) {
                        repi.closeEnv();
                        break;
                    }
                }
            }

            /* Run a workload that will create cleanable waste. */
            doWastefulWork(master);

            /*
             * Check how the replication stream has grown, and what the last
             * VLSN is now.
             */
            VLSN lastVLSN = vlsnIndex.getRange().getLast();
            RepTestUtils.syncGroupToVLSN(repEnvInfo,
                                         (killOneNode ?
                                          repEnvInfo.length-1 :
                                          repEnvInfo.length),
                                         lastVLSN);
            Thread.sleep(heartbeatMS * 3);
            lastVLSN = vlsnIndex.getRange().getLast();

            /* Run cleaning on each node. */
            for (RepEnvInfo repi : repEnvInfo) {
                cleanLog(repi.getEnv());
            }

            /* Check VLSN index */
            for (RepEnvInfo repi : repEnvInfo) {
                if (repi.getEnv() == null) {
                    continue;
                }
                vlsnIndex =
                    RepInternal.getRepImpl(repi.getEnv()).getVLSNIndex();
                VLSNRange range = vlsnIndex.getRange();
                logger.info(repi.getEnv().getNodeName() +
                	    "===>rangefirst=" + range.getFirst() +
                            " lastVLSN=" + lastVLSN);
                //assertTrue(lastVLSN.compareTo(range.getFirst()) >= 0);
                if (killOneNode) {
                    assertEquals(1, range.getFirst().getSequence());
                } else {
                    /*
                     * Most of the replication stream should have been
                     * cleaned away. There should not be any more than 20
                     * VLSNs left.
                     */
                    assertTrue("Start of stream at " + range.getFirst(),
                               (range.getFirst().getSequence() > 1000));
                }
                EnvironmentStats stats = repi.getEnv().getStats(statsConfig);
                assertEquals("cleanerBacklog should be zero ", 0,
                             stats.getCleanerBacklog());

                if (!killOneNode) {
                    assertTrue("File deletion backlog should be small",
                                (stats.getFileDeletionBacklog() < 10));
                } else {
                    /*
                     * For now, we have no optimization that lets us clean
                     * files with no VLSNs if they are within the files in
                     * the VLSN range, so there will be a deletion backlog.
                     */
                    logger.info("deletion backlog" +
                                stats.getFileDeletionBacklog());
                }
            }
        } catch(Exception e) {
                e.printStackTrace();
                throw e;
        } finally {
            if (repEnvInfo != null) {
                for (RepEnvInfo repi : repEnvInfo) {
                    if (repi.getEnv() != null) {
                        repi.closeEnv();
                    }
                }
            }
        }
    }

    private void doWastefulWork(ReplicatedEnvironment master) {
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setTransactional(true);
        dbConfig.setAllowCreate(true);
        Database db = master.openDatabase(null, "test", dbConfig);
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry(new byte[100]);

        TransactionConfig txnConfig = new TransactionConfig();
        txnConfig.setDurability
            (new Durability(SyncPolicy.NO_SYNC,
                            SyncPolicy.NO_SYNC,
                            ReplicaAckPolicy.SIMPLE_MAJORITY));
        try {
            for (int i = 0; i < 100; i++) {
                IntegerBinding.intToEntry(i, key);
                Transaction txn = master.beginTransaction(null, txnConfig);
                for (int repeat = 0; repeat < 30; repeat++) {
                    db.put(txn, key, data);
                }
                db.delete(txn, key);
                txn.commit();
            }

            /* One more synchronous one to flush the log files. */
            IntegerBinding.intToEntry(101, key);
            txnConfig.setDurability
                (new Durability(SyncPolicy.SYNC,
                                SyncPolicy.SYNC,
                                ReplicaAckPolicy.SIMPLE_MAJORITY));
            Transaction txn = master.beginTransaction(null, txnConfig);
            db.put(txn, key, data);
            db.delete(txn, key);
            txn.commit();
        } finally {
            db.close();
        }
    }

    private void cleanLog(ReplicatedEnvironment repEnv) {
        if (repEnv == null) {
            return;
        }

        CheckpointConfig force = new CheckpointConfig();
        force.setForce(true);

        EnvironmentStats stats = repEnv.getStats(new StatsConfig());
        int numCleaned = 0;
        int cleanedThisRun = 0;
        long beforeNFileDeletes = stats.getNCleanerDeletions();
        while ((cleanedThisRun = repEnv.cleanLog()) > 0) {
            numCleaned += cleanedThisRun;
        }
        repEnv.checkpoint(force);

        while ((cleanedThisRun = repEnv.cleanLog()) > 0) {
            numCleaned += cleanedThisRun;
        }
        repEnv.checkpoint(force);

        logger.info("numCleanedFiles = " + numCleaned);

        stats = repEnv.getStats(new StatsConfig());
        long afterNFileDeletes = stats.getNCleanerDeletions();
        long actualDeleted = afterNFileDeletes - beforeNFileDeletes;

        logger.info(repEnv.getNodeName() +
                    " cbvlsn=" +
                    RepInternal.getRepImpl(repEnv).
                    getRepNode().getGroupCBVLSN() +
                    " deleted files = " + actualDeleted +
                    " numCleaned=" + numCleaned);
    }
}
