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
import java.io.IOException;

import org.junit.Before;
import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.LongBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Durability;
import com.sleepycat.je.Durability.ReplicaAckPolicy;
import com.sleepycat.je.Durability.SyncPolicy;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.log.FileManager;
import com.sleepycat.je.rep.NoConsistencyRequiredPolicy;
import com.sleepycat.je.rep.RepInternal;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.ReplicationConfig;
import com.sleepycat.je.rep.impl.RepGroupImpl;
import com.sleepycat.je.rep.impl.RepImpl;
import com.sleepycat.je.rep.impl.RepNodeImpl;
import com.sleepycat.je.rep.impl.RepParams;
import com.sleepycat.je.rep.impl.RepTestBase;
import com.sleepycat.je.rep.stream.FeederReader;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.je.utilint.VLSN;

public class CBVLSNTest extends RepTestBase {

    /* enable debugging printlns with -Dverbose=true */
    private final boolean verbose = Boolean.getBoolean("verbose");

    /**
     * Don't rely on RepTestBase setup, because we want to specify the
     * creation of the environment config.
     */
    @Before
    public void setUp()
        throws Exception {

        RepTestUtils.removeRepEnvironments(envRoot);
        dbconfig = new DatabaseConfig();
        dbconfig.setAllowCreate(true);
        dbconfig.setTransactional(true);
        dbconfig.setSortedDuplicates(false);
    }

    @Test
    public void testBasic()
        throws Exception {

        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);

        /*
         * Turn off the cleaner because this test is doing scans of the log
         * file for test purposes, and those scans are not coordinated with
         * the cleaner.
         */
        envConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CLEANER, "false");

        File[] dirs = RepTestUtils.makeRepEnvDirs(envRoot, groupSize);
        repEnvInfo = new RepEnvInfo[groupSize];
        for (int i = 0; i < groupSize; i++) {

            /*
             * Mimic the default (variable per-env) log file sizing behavior
             * that would occur when this test case is the first one to be
             * executed.  (Since the order of execution is unpredictable, it's
             * unsound to rely on getting this behavior without explicitly
             * coding it here.)  It makes replicas have larger log files than
             * the master.  With five nodes, it tends to mean that the last
             * replica is still using a single log file, and its local CBVLSN
             * lags behind, and gates the global CBVLSN advancement.
             */ 
            long size = (i + 2) * 10000;
            EnvironmentConfig ec = envConfig.clone();
            DbInternal.disableParameterValidation(ec);
            ec.setConfigParam(EnvironmentConfig.LOG_FILE_MAX,
                              Long.toString(size));
            ReplicationConfig rc =
                RepTestUtils.createRepConfig(i + 1);
            repEnvInfo[i] =
                RepTestUtils.setupEnvInfo(dirs[i],
                                          ec,
                                          rc,
                                          repEnvInfo[0]);
        }
        checkCBVLSNs(500, 2);
    }

    /* 
     * Because this test case sets a very small log file size,
     * as a result, the GlobalCBVLSN would advance when the whole group 
     * starts up and an InsufficientLogException would be thrown when a node
     * tries to join the group at the start up, which is unexpected.
     *
     * To avoid this, disable the LocalCBVLSN updates on the master while 
     * starting up the group and enable it after the whole group is up.
     */
    @Test
    public void testSmallFiles()
        throws Exception {

        /*
         * Use uniformly small log files, which means that local CBVLSNs will
         * advance frequently, and the global CBVLSN will advance.
         */
        EnvironmentConfig smallFileConfig = new EnvironmentConfig();
        DbInternal.disableParameterValidation(smallFileConfig);
        smallFileConfig.setConfigParam(EnvironmentConfig.LOG_FILE_MAX, "1000");
        smallFileConfig.setAllowCreate(true);
        smallFileConfig.setTransactional(true);

        /*
         * Turn off the cleaner because this test is doing scans of the log
         * file for test purposes, and those scans are not coordinated with
         * the cleaner.
         */
        smallFileConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CLEANER,
                                       "false");
        repEnvInfo = RepTestUtils.setupEnvInfos(envRoot,
                                                groupSize,
                                                smallFileConfig);
        checkCBVLSNs(60, 5, true);
    }

    /**
     * Check that nodes which are in the group but have a null local 
     * CBVLSN do an adequate job of holding back the global CBVLSN update.
     * [#17522]
     * @throws IOException 
     */
    @Test
    public void testNewNodeThrottle() 
        throws IOException {

        /**
         * Make a group, but make the replica nodes leave before any
         * substantial work has happened. They will have some VLSNs, but
         * not many.
         */
        repEnvInfo = RepTestUtils.setupEnvInfos(envRoot, groupSize);
        createGroup();
        ReplicatedEnvironment mrep = repEnvInfo[0].getEnv();
        leaveGroupAllButMaster();

        /* Do a lot of real work, so that the global cbvlsn wants to advance. */
        populateDB(mrep, TEST_DB_NAME, 10000);

        /* 
         * Make sure that master's global cbvlsn was throttled enough so that
         * the previously sleeping nodes can still come up. 
         */
        for (int i=1; i < repEnvInfo.length; i++) {
            RepEnvInfo ri = repEnvInfo[i];
            ri.openEnv(new NoConsistencyRequiredPolicy());
        }
    }

    private void checkCBVLSNs(int numRecords,
                              int filesToUse)
        throws Exception {

        checkCBVLSNs(numRecords, filesToUse, false);
    }

    private void checkCBVLSNs(int numRecords,
                              int filesToUse,
                              boolean disableLocalCBVLSNUpdate)
        throws Exception {

        if (!disableLocalCBVLSNUpdate) {
            RepTestUtils.joinGroup(repEnvInfo);
        } else {
            /* Open the master. */
            repEnvInfo[0].openEnv();
            /* Disable the LocalCBVLSN updates on the master. */
            RepInternal.getRepImpl(repEnvInfo[0].getEnv()).getRepNode().
                getCBVLSNTracker().setAllowUpdate(false);

            /* 
             * Open other replicas one by one, so that no 
             * InsufficientLogExceptoin will be thrown.
             */
            for (int i = 1; i < repEnvInfo.length; i++) {
                repEnvInfo[i].openEnv();
            }

            /* Enable the LocalCBVLSN updates again. */
            RepInternal.getRepImpl(repEnvInfo[0].getEnv()).getRepNode().
                getCBVLSNTracker().setAllowUpdate(true);
        }

        /* Master is the first node. */
        ReplicatedEnvironment mRepEnv = repEnvInfo[0].getEnv();
        assertTrue(mRepEnv.getState().isMaster());

        Database db = mRepEnv.openDatabase(null, TEST_DB_NAME, dbconfig);

        try {

            /*
             * Do work on the master, and check that its local CBVLSN advances.
             */
            workAndCheckLocalCBVLSN(mRepEnv,
                                    db,
                                    RepTestUtils.SYNC_SYNC_ALL_TC,
                                    numRecords,
                                    filesToUse);

            /* Make sure that all the replicas send in their local CBVLSNs. */
            doGroupWideChecks(mRepEnv,
                              false,  // oneStalledReplica
                              VLSN.NULL_VLSN);

            /*
             * Crash one node, and resume execution. Make sure that the dead
             * node holds back the global CBVLSN. Run this set with quorum
             * acks.
             */
            if (verbose) {
                System.out.println("crash one node");
            }
            repEnvInfo[repEnvInfo.length - 1].getEnv().close();

            VLSN currentGlobalCBVLSN =
                RepInternal.getRepImpl(mRepEnv).getRepNode().getGroupCBVLSN();
            TransactionConfig tConfig = new TransactionConfig();
            tConfig.setDurability
                (new Durability(SyncPolicy.SYNC, SyncPolicy.SYNC,
                                ReplicaAckPolicy.SIMPLE_MAJORITY));
            workAndCheckLocalCBVLSN(mRepEnv,
                                    db,
                                    tConfig,
                                    numRecords,
                                    filesToUse);
            if (verbose) {
                System.out.println("group wide check");
            }

            doGroupWideChecks(mRepEnv,
                              true, // oneStalledReplica
                              currentGlobalCBVLSN);

        } catch(Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            db.close();
        }
    }

    /**
     * @param padPreventAdvancement if true, the je.rep.cbvlsn.pad property
     * is large enough to prevent any CBVLSN advancement.
     * @throws InterruptedException
     */
    private void workAndCheckLocalCBVLSN(ReplicatedEnvironment mRepEnv,
                                         Database db,
                                         TransactionConfig tConfig,
                                         int numRecords,
                                         int numFilesToUse)
        throws InterruptedException {

        RepImpl mRepImpl = RepInternal.getRepImpl(mRepEnv);
        RepNode mNode = mRepImpl.getRepNode();
        final FileManager mFileManager = mRepImpl.getFileManager();
        String mName =  mRepEnv.getNodeName();
        long logFileNum = mFileManager.getLastFileNum().longValue();
        long startFileNum = logFileNum;
        VLSN barrierVLSN =
            mNode.getGroup().getMember(mName).getBarrierState().getLastCBVLSN();

        /*
         * Tests that every log file switch results in a local CBVLSN update on
         * the master.
         */
        int mId = mRepImpl.getNodeId();
        for (int i=0; i < numRecords; i++) {
            Transaction txn = mRepEnv.beginTransaction(null, tConfig);
            IntegerBinding.intToEntry(i, key);
            LongBinding.longToEntry(i, data);
            db.put(txn, key, data);
            txn.commit();

            long newFileNum = mFileManager.getLastFileNum().longValue();
            if (logFileNum < newFileNum) {
                if (verbose) {
                    System.out.println("master's global CBVLSN =" +
                                       mNode.getGroupCBVLSN());
                }
                logFileNum = newFileNum;

                /*
                 * We should have moved up to a new local CBVLSN.
                 * localCBVLSNVal is updated directly, so it's sure to have
                 * advanced.
                 */
                VLSN newLocalCBVLSN = 
                    mNode.getCBVLSNTracker().getBroadcastCBVLSN();

                if (!barrierVLSN.isNull()) {

                    /* 
                     * if the barrier vlsn is null, we'd expect the first
                     * non-null vlsn to update the localcblvlsn state, so
                     * only to this check for if we're not starting out at
                     * null.
                     */
                    String info = "newLocal=" + newLocalCBVLSN +
                        " prevLocal=" + barrierVLSN;
                    assertTrue(info, 
                               newLocalCBVLSN.compareTo(barrierVLSN) >= 0);
                }

                barrierVLSN = newLocalCBVLSN;

                /*
                 * Check that the new local CBVLSN value is in the cached group
                 * info, and the database. The group info and the database are
                 * only updated by the master when it is running in the
                 * FeederManager loop, so we will retry once if needed.
                 */
                int retries = 2;
                VLSN indbVLSN = null;
                while (retries-- > 0) {
                mNode.refreshCachedGroup();
                    indbVLSN = mNode.getGroup().getMember(mId).
                        getBarrierState().getLastCBVLSN();

                    if (!indbVLSN.equals(newLocalCBVLSN)) {
                        /* Wait one feederManager channel polling cycle. */
                        Thread.sleep(1000);
                        continue;
                    }
                }

                assertEquals("local=" + newLocalCBVLSN +
                             " inDbVLSN=" + indbVLSN,
                             newLocalCBVLSN, indbVLSN);
            }
        }

        /*
         * Test that at least two log files worth of data have been generated,
         * so the conditional above is exercised.
         */
        assertTrue(logFileNum > (startFileNum + 1));

        /*
         * Test that we exercised a certain number of log files on the
         * master, since local CBVLSNs are broadcast at file boundaries.
         */
        assertTrue("logFileNum=" + logFileNum +
                   " startFileNum=" + startFileNum +
                   " numFilesToUse=" + numFilesToUse,
                   (logFileNum - startFileNum) >= numFilesToUse);
        if (verbose) {
            System.out.println("logFileNum = " + logFileNum +
                               " start=" + startFileNum);
        }
    }

    /**
     * Check replicas for local CBVLSN value consistency wrt the current
     * master. Also check that the global CBVLSN has been advancing, if
     * appropriate.
     * @throws IOException
     */
    private void doGroupWideChecks(ReplicatedEnvironment mRepEnv,
                                   boolean oneReplicaStalled,
                                   VLSN stalledGlobalCBVLSN)
        throws DatabaseException, InterruptedException, IOException {

        RepNode mNode = RepInternal.getRepImpl(mRepEnv).getRepNode();

        /* Ensure that all replicas have reasonably current syncup values. */
        int replicaCount = 0;
        for (RepEnvInfo repi : repEnvInfo) {
            ReplicatedEnvironment rep = repi.getEnv();
            if (RepInternal.isClosed(rep)) {
                continue;
            }
            replicaCount++;

            RepNode repNode = RepInternal.getRepImpl(rep).getRepNode();
            final int nodeId = repNode.getNodeId();
            final int heartBeatMs =
                Integer.parseInt(rep.getRepConfig().
                                 getConfigParam(RepParams.
                                                HEARTBEAT_INTERVAL.getName()));
            final int retryWaitMs = 1000;

            /*
             * Each replicator will result in a roundtrip update, thus taking
             * more time for the group as a whole to reach stasis. So increase
             * the number of retries based on the number of replicators.
             */
            int retries = (int) ((repEnvInfo.length * 1.5) * heartBeatMs) /
                retryWaitMs;

            if (verbose) {
                System.out.println("start retries = " + retries);
            }

            /*
             * Check to ensure they are reasonably current at the node itself.
             */
            while (true) {
                assertTrue(retries-- > 0);
                long efileNum = repNode.getRepImpl().getFileManager().
                                getLastFileNum().longValue();

                VLSN masterPerNodeLastSync = mNode.getGroup().
                                                   getMember(nodeId).
                                                   getBarrierState().
                                                   getLastCBVLSN();
                long vfileNum = getFileNumber(repNode, masterPerNodeLastSync);

                if  (!(vfileNum == efileNum ||(vfileNum+1) == efileNum)) {

                    /*
                     * Not near enough to the end of the replica's log. The
                     * Replica is still processing the replication stream. See
                     * if it needs to catch up.
                     */
                    Thread.sleep(retryWaitMs);
                    continue; // retry
                }

                /*
                 * Check that there is group CBVLSN agreement across the entire
                 * group.
                 */
                if (verbose) {
                    System.out.println(rep.getNodeName() +
                                       " retries=" + retries +
                                       " group check: global CBVLSN =" +
                                       mNode.getGroupCBVLSN() +
                                       " localCBVLSN = " +
                                       masterPerNodeLastSync);
                }

                /*
                 * Now that the replica has broadcast a local CBVLSN near the
                 * end of its log, check that everyone agrees on the global
                 * CBVLSN value.
                 */
                if (oneReplicaStalled) {

                    /*
                     * The dead replica should hold everyone to the same global
                     * CBVLSN.
                     */
                    assertEquals(dumpGroup(rep), stalledGlobalCBVLSN,
                                 mNode.getGroupCBVLSN());
                    assertEquals(dumpGroup(rep), stalledGlobalCBVLSN,
                                 repNode.getGroupCBVLSN());
                } else {
                    if (!mNode.getGroupCBVLSN().equals
                        (repNode.getGroupCBVLSN())) {
                        Thread.sleep(1000);
                        continue; // retry
                    }
                }
                break;
            }
        }

        /* We should have checked all the live replicas. */
        assertEquals(replicaCount,
                     (oneReplicaStalled ? repEnvInfo.length - 1 :
                      repEnvInfo.length));
    }

    private String dumpGroup(ReplicatedEnvironment repEnv) {

        RepNode repNode = RepInternal.getRepImpl(repEnv).getRepNode();
        StringBuilder sb = new StringBuilder();
        sb.append(repEnv.getNodeName()).append("\n");
        for (RepNodeImpl n :  repNode.getGroup().getAllElectableMembers()) {
            sb.append(n.getName()).append(" ").append(n.getBarrierState());
        }
        return sb.toString();
    }

    /**
     * Find the log file that houses this vlsn. This does an exact match, and
     * may have to scan the logs.
     * @throws IOException
     * @throws DatabaseException
     * @throws InterruptedException
     */
    private long getFileNumber(RepNode repNode, VLSN vlsn)
        throws InterruptedException, DatabaseException, IOException {

        /*
         * If a node hasn't reported any CBVLSN yet, it must still be in file 0.
         */
        if (vlsn.isNull()) {
            return 0;
        }
        FeederReader reader =
            new FeederReader(repNode.getRepImpl(),
                             repNode.getVLSNIndex(),
                             DbLsn.NULL_LSN,
                             10000,
                             repNode.getNameIdPair());
        reader.initScan(vlsn);
        reader.scanForwards(vlsn, 0 /* waitTime */);
        return DbLsn.getFileNumber(reader.getLastLsn());
    }

    @Test
    public void testDbUpdateSuppression()
        throws DatabaseException, InterruptedException, IOException {

        repEnvInfo = RepTestUtils.setupEnvInfos(envRoot, groupSize);

        /* Turn off all cleaners. */
        for (RepEnvInfo ri : repEnvInfo) {
            ri.getEnvConfig().setConfigParam
                (EnvironmentParams.ENV_RUN_CLEANER.getName(), "false");
        }

        ReplicatedEnvironment mEnv = RepTestUtils.joinGroup(repEnvInfo);
        assertEquals(ReplicatedEnvironment.State.MASTER, mEnv.getState());
        RepImpl repInternal = RepInternal.getRepImpl(mEnv);
        RepNode masterNode = repInternal.getRepNode();
        LocalCBVLSNUpdater.setSuppressGroupDBUpdates(true);
        final FileManager masterFM = repInternal.getFileManager();
        RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);

        RepGroupImpl group1 = masterNode.getGroup();
        long fileNum1 = masterFM.getLastFileNum().longValue();

        Database db = mEnv.openDatabase(null, TEST_DB_NAME, dbconfig);

        /* Force two new log files. */
        for (int i=0; true; i++) {
            IntegerBinding.intToEntry(i, key);
            LongBinding.longToEntry(i, data);
            db.put(null, key, data);
            if (masterFM.getLastFileNum().longValue() > (fileNum1+1)) {
                break;
            }
        }
        RepGroupImpl group2 = masterNode.getGroup();
        for (RepNodeImpl n1 : group1.getAllElectableMembers()) {
            RepNodeImpl n2 = group2.getMember(n1.getNodeId());
            assertEquals(n1.getBarrierState(), n2.getBarrierState());
        }
        db.close();
        LocalCBVLSNUpdater.setSuppressGroupDBUpdates(false);
    }
}
