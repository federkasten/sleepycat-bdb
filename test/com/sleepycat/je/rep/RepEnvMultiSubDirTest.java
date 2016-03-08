/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.CommitToken;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.rep.impl.RepImplStatDefinition;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.util.DbTruncateLog;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/**
 * Test that replication group with je.log.nDataDirectories enabled can work
 * correctly. It tests the basic operations, hard recovery, NetworkBackup is
 * tested in com.sleepycat.je.rep.impl.networkRestore.NetworkBackupTest.
 */
public class RepEnvMultiSubDirTest extends TestBase {
    private static final int DATA_DIRS = 3;
    private static final String DB_NAME = "testDb";
    private static final String keyPrefix = "herococo";
    private static final String dataValue = "abcdefghijklmnopqrstuvwxyz";

    private final File envRoot;
    private RepEnvInfo[] repEnvInfo;

    public RepEnvMultiSubDirTest() {
        envRoot = SharedTestUtils.getTestDir();
    }

    private EnvironmentConfig createEnvConfig(boolean noAckDurability) {
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);
        if (noAckDurability) {
            envConfig.setDurability(RepTestUtils.SYNC_SYNC_NONE_DURABILITY);
        }

        /* 
         * Configure a small log file size so that the log files can spread in 
         * the sub directories. 
         */
        DbInternal.disableParameterValidation(envConfig);
        envConfig.setConfigParam(EnvironmentConfig.LOG_FILE_MAX,
                                 "10000");

        /*
         * Configure a small checkpointer and cleaner interval bytes, so that
         * checkpointer and cleaner can be invoked more frequently to do the 
         * cleaning work.
         */
        envConfig.setConfigParam(EnvironmentConfig.CHECKPOINTER_BYTES_INTERVAL,
                                 "20000");
        envConfig.setConfigParam(EnvironmentConfig.CLEANER_BYTES_INTERVAL,
                                 "10000");
        envConfig.setConfigParam(EnvironmentConfig.LOG_N_DATA_DIRECTORIES,
                                 DATA_DIRS + "");

        return envConfig;
    }

    private DatabaseConfig createDbConfig() {
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setTransactional(true);

        return dbConfig;
    }

    /*
     * Test the basic database operations on both master and replicas. 
     */
    @Test
    public void testRepBasic() 
        throws Throwable {

        try {
            repEnvInfo = 
                RepTestUtils.setupEnvInfos(envRoot, 3, createEnvConfig(false));
            RepTestUtils.createRepSubDirs(repEnvInfo, DATA_DIRS);
            
            ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);
            checkNodeStates(0);

            /* 
             * Do enough updates to make sure log files spread to all sub 
             * directories. 
             */
            doUpdatesOnMaster(master);

            /* Sync group to make sure records are replayed on the replicas. */
            RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);

            /* Check that records can be read correctly on replicas. */
            for (int i = 1; i < repEnvInfo.length; i++) {
                checkContents(repEnvInfo[i].getEnv(), 1001, 2000, 
                              dataValue + dataValue);
            }

            repEnvInfo[0].closeEnv();

            /* Make sure the mastership has changed. */
            int counter = 10;
            while (!repEnvInfo[2].isMaster() && --counter > 0) {
                Thread.sleep(1000);
            }
            assertTrue(repEnvInfo[2].isMaster());

            /* Reopen the replica. */
            repEnvInfo[0].openEnv();

            /* Check the contents on the former master. */
            checkContents
                (repEnvInfo[0].getEnv(), 1001, 2000, dataValue + dataValue);
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        } finally {
            RepTestUtils.shutdownRepEnvs(repEnvInfo);
        }
    }

    private void checkNodeStates(int masterIndex) {
        for (int i = 0; i < repEnvInfo.length; i++) {
            if (i == masterIndex) {
                assertTrue(repEnvInfo[i].isMaster());
            } else {
                assertTrue(repEnvInfo[i].isReplica());
            }
        }
    }

    private void doUpdatesOnMaster(ReplicatedEnvironment master) 
        throws Exception {

        Database db = master.openDatabase(null, DB_NAME, createDbConfig());

        /* Insert data. */
        insertData(db, null, 1, 2000, dataValue);

        /* Delete data. */
        DatabaseEntry key = new DatabaseEntry();
        for (int i = 1; i <= 100; i++) {
            Transaction txn = master.beginTransaction(null, null);
            for (int j = 1; j <= 10; j++) {
                StringBinding.stringToEntry(keyPrefix + (i * 10 + j), key);
                assertEquals(OperationStatus.SUCCESS, db.delete(txn, key));
            }
            txn.commit();
        }

        /* Update data. */
        insertData(db, null, 1001, 2000, dataValue + dataValue);
        db.close();
    }

    private void insertData(Database db, 
                            Transaction txn, 
                            int start, 
                            int end, 
                            String value) 
        throws Exception {

        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        for (int i = start; i <= end; i++) {
            StringBinding.stringToEntry(keyPrefix + i, key);
            StringBinding.stringToEntry(value, data);
            assertEquals(OperationStatus.SUCCESS, db.put(txn, key, data));
        }
    }

    private void checkContents(ReplicatedEnvironment repEnv, 
                               int start, 
                               int end,
                               String value)
        throws Exception {

        Database db = repEnv.openDatabase(null, DB_NAME, createDbConfig());

        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        for (int i = start; i <= end; i++) {
            StringBinding.stringToEntry(keyPrefix + i, key);
            assertEquals(OperationStatus.SUCCESS,
                         db.get(null, key, data, null));
            assertEquals(StringBinding.entryToString(data), value);
        }
        db.close();
    }

    /*
     * Test that hard recovery can work correctly on a multi sub directories 
     * replica. 
     */
    @Test
    public void testHardRecovery()
        throws Throwable {

        ArrayList<String> formerLines = null;
        try {
            /* Expect RollbackProhibitedException when hard recovery happen. */
            ReplicationConfig repConfig = new ReplicationConfig();
            repConfig.setConfigParam
                (ReplicationConfig.TXN_ROLLBACK_LIMIT, "0");

            /* 
             * Write the configuration to je.properties so that it can be read
             * by the utility environment.
             */
            formerLines = TestUtils.readWriteJEProperties
                (envRoot, "je.log.nDataDirectories=" + DATA_DIRS);
            
            /* Start the whole replication group. */
            repEnvInfo = RepTestUtils.setupEnvInfos(envRoot, 
                                                    3, 
                                                    createEnvConfig(true), 
                                                    repConfig);
            RepTestUtils.createRepSubDirs(repEnvInfo, DATA_DIRS);

            ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);
            checkNodeStates(0);

            Database db = master.openDatabase(null, DB_NAME, createDbConfig());
            
            RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);

            /* Shut down replicas so that they don't see the commit. */
            for (int i = 1; i < repEnvInfo.length; i++) {
                repEnvInfo[i].closeEnv();
            }

            /* Only insert data on the master and shutdown the master. */
            insertData(db, null, 1, 10, dataValue);
            db.close();
            checkContents(master, 1, 10, dataValue);
            repEnvInfo[0].closeEnv();

            /* 
             * Restart the replicas, and do some work to make they have 
             * different data as the former master to cause a hard recovery.
             */
            master = RepTestUtils.restartGroup(repEnvInfo[1], repEnvInfo[2]);
            db = master.openDatabase(null, DB_NAME, createDbConfig());

            Transaction txn = master.beginTransaction(null, null);
            insertData(db, txn, 101, 110, dataValue);
            txn.commit();
            CommitToken token = txn.getCommitToken();
            db.close();
            checkContents(master, 101, 110, dataValue);

            /* Restart the old master, expecting hard recovery. */
            try {
                repEnvInfo[0].openEnv
                    (new CommitPointConsistencyPolicy(token, 1000, 
                                                      TimeUnit.SECONDS));
                assertTrue(RepInternal.getRepImpl(repEnvInfo[0].getEnv()).
                           getNodeStats().
                           getBoolean(RepImplStatDefinition.HARD_RECOVERY));
            } catch (RollbackProhibitedException e) {

                /* 
                 * Expected exceptions, truncate the unmatched log on the old 
                 * master. 
                 */
                DbTruncateLog truncator = new DbTruncateLog();
                truncator.truncateLog(repEnvInfo[0].getEnvHome(),
                                      e.getTruncationFileNumber(),
                                      e.getTruncationFileOffset());

                /* Reopen the old master after truncation. */
                repEnvInfo[0].openEnv
                    (new CommitPointConsistencyPolicy(token, 1000,
                                                      TimeUnit.SECONDS));
            }
            assertTrue(repEnvInfo[0].isReplica());
            /* Check that old master has the newest log. */
            checkContents(repEnvInfo[0].getEnv(), 101, 110, dataValue);
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        } finally {
            RepTestUtils.shutdownRepEnvs(repEnvInfo);

            /* Remove the je.log.nDataDirectories from the je.properties. */
            if (formerLines != null) {
                TestUtils.reWriteJEProperties(envRoot, formerLines);
            }
        }
    }
}
