/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.sync.ChangeReader.Change;
import com.sleepycat.je.sync.ChangeReader.ChangeTxn;
import com.sleepycat.je.sync.ChangeReader.ChangeType;
import com.sleepycat.je.sync.SyncProcessor;
import com.sleepycat.je.sync.impl.ConcurrentSyncDataSetTest.AddTestThread;
import com.sleepycat.je.sync.impl.LogChangeReader;
import com.sleepycat.je.sync.test.MockMobileTestBase;
import com.sleepycat.je.sync.test.TestMobileSyncProcessor;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

public class RepDataSyncFailoverTest extends TestBase {
    private static final String processorName = "processor";
    private static final String dbName = "testDb";
    private static final String oldValue = "abcdefghijklmnopqrstuvwxyz";
    private static final String newValue = oldValue + "0123456789";

    private final File envRoot;
    private RepEnvInfo[] repEnvInfo;

    public RepDataSyncFailoverTest() {
        envRoot = SharedTestUtils.getTestDir();
    }

    /* 
     * Test that the LogChangeReader behaviors are correct after the master 
     * failover.
     *
     * This test will log 20 transactions on the master, and only export the 
     * first 10 transactions on the old master, then close the old master. And
     * do the export of the rest 10 transactions on the newly elected master,
     * check the contents to make sure they're reading the correct contents.
     */
    @Test
    public void testBasicFailover() 
        throws Throwable {

        try {
            repEnvInfo = RepSyncTestUtils.setupEnvInfos(3, envRoot);
            ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);
            assertTrue(master.getState().isMaster());

            Database db = RepSyncTestUtils.createDb(master, dbName);

            TestMobileSyncProcessor processor = 
                MockMobileTestBase.createProcessor(processorName, master);
            MockMobileTestBase.addDataSet(processor, new String[] { dbName });

            /* Do some log changes and save the transaction ids. */
            ArrayList<Long> txnIds = new ArrayList<Long>();
            logEntries(txnIds, db, master);

            /* Do a log file flip, so that all the changes can be read. */
            RepSyncTestUtils.doLogFileFlip(repEnvInfo);

            /* Add more log entries to increase the GlobalCBVLSN. */
            Database fakeDb = 
                RepSyncTestUtils.makeFakeEntries(master, oldValue);
            fakeDb.close();

            /* Sync the whole replication group. */
            RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);

            /* Do exporting of the first 10 transactions on the old master. */
            LogChangeReader reader = new LogChangeReader
                (master, dbName, processor, false, 0);
            Iterator<ChangeTxn> txns = reader.getChangeTxns();
            int counter = 1;
            while (txns.hasNext() && counter <= 10) {
                txns.next();
                reader.discardChanges(null);
                counter++;
            }
             
            /* Close the master. */
            db.close();
            master.close();

            /* Elect a new master. */
            master = RepTestUtils.openRepEnvsJoin(repEnvInfo);
            /* Create SyncProcessor and LogChangeReader on the new master. */
            processor = 
                MockMobileTestBase.createProcessor(processorName, master);
            reader = new LogChangeReader
                (master, dbName, processor, false, 0);
            /* Start to read the rest 10 transactions. */
            txns = reader.getChangeTxns();
            counter = 0;
            while (txns.hasNext()) {
                ChangeTxn txn = txns.next();
                /* Check that the ChangeTxn contents are expected. */
                assertTrue(txn.getTransactionId() == txnIds.get(counter + 10));
                assertTrue(txn.getDataSetName().equals(dbName));
                assertTrue(txn.getDatabaseNames().size() == 1);

                Iterator<Change> changes = txn.getOperations();
                int number = 0;
                while (changes.hasNext()) {
                    Change change = changes.next();
                    number++;
                    if (counter <= 4) {
                        assertEquals(change.getType(), ChangeType.UPDATE);
                        assertEquals
                            (newValue,
                             StringBinding.entryToString(change.getData()));
                        assertEquals
                            (counter * 100 + number,
                             IntegerBinding.entryToInt(change.getKey()));
                    } else if (counter <= 9) {
                        assertEquals(change.getType(), ChangeType.DELETE);
                        assertEquals(null, change.getData());
                        assertEquals
                            (counter * 100 + number,
                             IntegerBinding.entryToInt(change.getKey()));
                    } else {
                        fail("Counter shouldn't be larger than 20.");
                    }
                    assertEquals(change.getDatabaseName(), dbName);
                }
                assertEquals(number, 100);

                reader.discardChanges(null);
                counter++;
            }
            assertTrue(counter == 10);
        } finally {
            RepTestUtils.shutdownRepEnvs(repEnvInfo);
        }
    }

    /*
     * Test the following case won't fail:
     * 1. Thread A on the master is trying to add the first SyncDataSet, but it
     *    sleeps before writing the SyncCleanerBarrier.
     * 2. Thread B on the master wakes up, and do lots of database operations,
     *    so that the GlobalCBVLSN is advancing and larger than the endOfLog 
     *    got by thread A.
     * 3. Cleaners on the master wake up, but it is blocked by 
     *    SyncCleanerBarrier.getMinSyncStart, since it's blocked by the 
     *    SyncCleanerBarrier object.
     * 4. Cleaners on the replicas wake up, and they can delete the endOfLog
     *    on the replicas.
     * 5. The master dies and a replica is elected to be a master, it will try
     *    to read log entries on a deleted log file.
     */
    @Test
    public void testInvalidReadStartOnReplicaAfterFailover() 
        throws Throwable {

        try {

            /* 
             * Create the replication group, we'll disable the log cleaning for 
             * all replicas. 
             */
            repEnvInfo = 
                RepSyncTestUtils.setupEnvInfos(3, envRoot, true, false);
            ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);
            assertTrue(master.getState().isMaster());

            /* Create the database and the SyncProcessor. */
            Database db = RepSyncTestUtils.createDb(master, dbName);
            TestMobileSyncProcessor processor = 
                MockMobileTestBase.createProcessor(processorName, master);


            /* 
             * Sync up the group and do a log file flip, we want to make sure
             * that the log file where endOfLog lives should have only 2 or
             * three useful log entries so that it can be removed by the 
             * cleaner.
             */
            RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);
            RepSyncTestUtils.doLogFileFlip(repEnvInfo);

            CountDownLatch awaitLatch = new CountDownLatch(1);
            NewAddTestThread thread = new NewAddTestThread
                ("thread1", processor, dbName, true, awaitLatch, 1000);
            thread.start();

            /* Wait until the endOfLog is got by thread. */
            if (!thread.isInvoked()) {
                Thread.sleep(100);
            }

            /* 
             * Do a log file flip again so that that log file has very few
             * useful log entries.
             */
            RepSyncTestUtils.doLogFileFlip(repEnvInfo);

            /* Do some database operations to make the GlobalCBVLSN changes. */
            ArrayList<Long> txnIds = new ArrayList<Long>();
            logEntries(txnIds, db, master);

            /* Insert large data to make the GlobalCBVLSN increases. */
            Database fakeDb = 
                RepSyncTestUtils.makeFakeEntries(master, oldValue, false);
            fakeDb.close();
            RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);

            /* Do log cleaning on each replica. */
            for (int i = 0; i < repEnvInfo.length; i++) {
                if (!repEnvInfo[i].isMaster()) {
                    CheckpointConfig ckptConfig = new CheckpointConfig();
                    ckptConfig.setForce(true);
                    int cleanedLogFile = repEnvInfo[i].getEnv().cleanLog();
                    repEnvInfo[i].getEnv().checkpoint(ckptConfig);
                    assertTrue(cleanedLogFile >= 1);
                }
            }

            /* Finish the thread that adds the SyncDataSet. */
            awaitLatch.countDown();
            thread.finishTest();
            assertFalse(thread.isFailed());

            /* Close the master to simulate the failover. */
            db.close();
            repEnvInfo[0].closeEnv();

            /* Elect a new master again. */
            try {
                master = RepTestUtils.openRepEnvsJoin(repEnvInfo);
                assertTrue(master.getState().isMaster());

                /* Create the SyncProcessor and reader on the new master. */
                processor = 
                    MockMobileTestBase.createProcessor(processorName, master);
                LogChangeReader reader = new LogChangeReader
                    (master, dbName, processor, false, 0);

                /* 
                 * Insert new data, so that when the old master comes back, it
                 * won't be elected as the master.
                 */
                final String secDbName = "secDb";
                Database secDb = RepSyncTestUtils.createDb(master, secDbName);
                RepSyncTestUtils.doDatabaseWork
                    (secDb, master, 1, 1, oldValue, false);

                /* Bring back the old master, check master doesn't change. */
                repEnvInfo[0].openEnv();
                assertTrue(master.getState().isMaster());
                assertTrue(repEnvInfo[0].isReplica());

                /* 
                 * Do more database operations to advance the GlobalCBVLSN, 
                 * so that the former data can be read.
                 */
                RepSyncTestUtils.doLogFileFlip(repEnvInfo);
                RepSyncTestUtils.doDatabaseWork
                    (secDb, master, 2, 100, oldValue, false);
                RepSyncTestUtils.doLogFileFlip(repEnvInfo);
                secDb.close();

                /* Read data on the new master and do the check. */
                int counter = 0;
                Iterator<ChangeTxn> txns = reader.getChangeTxns();
                while (txns.hasNext()) {
                    ChangeTxn txn = txns.next();
                    /* Check the ChangeTxn contents are expected. */
                    assertTrue(txn.getTransactionId() == txnIds.get(counter));
                    assertTrue(txn.getDataSetName().equals(dbName));
                    assertTrue(txn.getDatabaseNames().size() == 1);

                    Iterator<Change> changes = txn.getOperations();
                    int number = 0;
                    while (changes.hasNext()) {
                        Change change = changes.next();
                        number++;
                        ChangeType type = change.getType();
                        String value = (change.getData() == null) ? null :
                            StringBinding.entryToString(change.getData());
                        int key = IntegerBinding.entryToInt(change.getKey());
                        if (counter <= 9) {
                            assertEquals(type, ChangeType.INSERT);
                            assertEquals(value, oldValue);
                            assertEquals(key, counter * 100 + number);
                        } else if (counter <= 14) {
                            assertEquals(type, ChangeType.UPDATE);
                            assertEquals(value, newValue);
                            assertEquals(key, (counter - 10) * 100 + number);
                        } else if (counter <= 19) {
                            assertEquals(type, ChangeType.DELETE);
                            assertEquals(value, null);
                            assertEquals(key, (counter - 10) * 100 + number);
                        }
                        assertEquals(change.getDatabaseName(), dbName);
                    }
                    assertEquals(number, 100);

                    reader.discardChanges(null);
                    counter++;
                }
                assertEquals(counter, 20);
            } catch (Throwable t) {
                t.printStackTrace();
                fail("Expect no exceptions.");
            }
        } finally {
            RepTestUtils.shutdownRepEnvs(repEnvInfo);
        }
    }

    /* Do database entries for the SyncDataSet. */
    private void logEntries(ArrayList<Long> txnIds, 
                            Database db, 
                            ReplicatedEnvironment env) 
        throws Exception {

        for (int i = 1; i <= 10; i++) {
            txnIds.add(RepSyncTestUtils.doDatabaseWork
                    (db, env, (i - 1) * 100 + 1, i * 100, oldValue, false));
        }

        for (int i = 1; i <= 5; i++) {
            txnIds.add(RepSyncTestUtils.doDatabaseWork
                    (db, env, (i - 1) * 100 + 1, i * 100, newValue, false));
        }

        for (int i = 6; i <= 10; i++) {
            txnIds.add(RepSyncTestUtils.doDatabaseWork
                    (db, env, (i - 1) * 100 + 1, i * 100, null, true));
        }
    }

    public static class NewAddTestThread extends AddTestThread {
        public NewAddTestThread(String threadName,
                                SyncProcessor processor,
                                String dbName,
                                boolean enableHook,
                                CountDownLatch awaitLatch,
                                long waitSeconds) {
            super(threadName, processor, dbName, 
                  enableHook, awaitLatch, waitSeconds);
        }

        @Override
        protected void doThreadWork() {
            if (enableHook) {
                processor.setAddHook(testHook);
            }

            try {
                MockMobileTestBase.addDataSet
                    ((TestMobileSyncProcessor) processor, 
                     new String[] { dbName });
            } catch (EnvironmentFailureException e) {
                failure = true;
                throw e;
            }
        }
    }
}
