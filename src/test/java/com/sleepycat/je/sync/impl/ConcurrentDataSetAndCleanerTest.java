package com.sleepycat.je.sync.impl;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.junit.JUnitThread;
import com.sleepycat.je.log.Trace;
import com.sleepycat.je.sync.ChangeReader.ChangeTxn;
import com.sleepycat.je.sync.SyncDatabase;
import com.sleepycat.je.sync.SyncProcessor;
import com.sleepycat.je.sync.impl.ConcurrentSyncDataSetTest.AddTestThread;
import com.sleepycat.je.sync.impl.ConcurrentSyncDataSetTest.RemoveTestThread;
import com.sleepycat.je.sync.impl.ConcurrentSyncDataSetTest.UpdateTestThread;
import com.sleepycat.je.sync.impl.SyncDataSetTest.MyMobileSyncProcessor;
import com.sleepycat.je.sync.mobile.MobileConnectionConfig;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/**
 * This test checks the concurrency between SyncDataSet, and the cleaner and
 * checkpointer. It mainly focuses on the relationship between minSyncStart
 * updates and cleaner barrier changes.
 */
public class ConcurrentDataSetAndCleanerTest extends TestBase {
    private final static String firstDbName = "firstDb";
    private final static String secDbName = "secDb";
    private final static String thirdDbName = "thirdDb";
    private final static String oldValue = "abcdefghijklmnopqrstuvwxyz";
    private final static String newValue = oldValue + " 0123456789";
    private final File envHome;
    private Environment env;
    private EnvironmentImpl envImpl;
    private Database firstDb;
    private Database secDb;
    private Database thirdDb;

    public ConcurrentDataSetAndCleanerTest() {
        envHome = SharedTestUtils.getTestDir();
    }

    @Before
    public void setUp() 
        throws Exception {

        super.setUp();
        /* Create the Environment. */
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);
    
        /*
         * Set smaller log file size, so that log cleaning can easily happen, 
         * disable the cleaner and checkpointer, since the test will invoke 
         * them directly.
         */
        DbInternal.disableParameterValidation(envConfig);
        envConfig.setConfigParam(EnvironmentConfig.LOG_FILE_MAX, "3000");
        envConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CLEANER, "false");
        envConfig.setConfigParam
            (EnvironmentConfig.ENV_RUN_CHECKPOINTER, "false");

        /*
         * Make sure the cleaner will do clean work if there exists any 
         * obsolete entries.
         */
        envConfig.setConfigParam
            (EnvironmentConfig.CLEANER_MIN_UTILIZATION, "99");
         
        env = new Environment(envHome, envConfig);
        envImpl = DbInternal.getEnvironmentImpl(env);

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setTransactional(true);
        firstDb = env.openDatabase(null, firstDbName, dbConfig);
        secDb = env.openDatabase(null, secDbName, dbConfig);
        thirdDb = env.openDatabase(null, thirdDbName, dbConfig);
    }

    @After
    public void tearDown() 
        throws Exception {

        try {
            TestUtils.closeAll(firstDb, secDb, thirdDb, env);
        } finally {
            firstDb = null;
            secDb = null;
            thirdDb = null;
            env = null;
        }
    }

    /* 
     * Test the following case won't happen:
     * 1. Thread A tries to add a new SyncDataSet, the current endOfLog lives
     *    in the first log file, but it sleeps between getting the endOfLog and
     *    writing the SyncCleanerBarrier.
     * 2. Thread B wakes up, it does lots of database operations to obsolete
     *    log entries, and invoke the cleaner and checkpointer at the end of
     *    that, so it tries to delete the first log file.
     * 3. Thread A wakes up, it doesn't know that the first log file has been
     *    deleted, so it will continue does reading, but it will try to read 
     *    an already deleted log file.
     */
    @Test
    public void testAddDataSetAndCleanerConcurency()
        throws Throwable {

        SyncProcessor firstProcessor = new MyMobileSyncProcessor
            (env, "test", new MobileConnectionConfig()); 

        /* 
         * Make sure the current endOfLog lives in the second log file, and 
         * only log a trace message in the second log file, so that this file
         * can be deleted during checkpointing. 
         */
        envImpl.forceLogFileFlip();
        Trace.trace(envImpl, "fake message.");
        assertTrue(DbLsn.getFileNumber(envImpl.getEndOfLog()) == 1);

        /* 
         * ThreadA will start first and wait 40 seconds to wait ThreadB does
         * log cleaning to delete the second log file.
         */ 
        CountDownLatch awaitLatch = new CountDownLatch(1);
        AddTestThread threadA = new AddTestThread
            ("thread1", firstProcessor, firstDbName, true, awaitLatch, 40);
        threadA.start();

        /* Wait until the endOfLog is set. */
        while (!threadA.isInvoked()) {
            Thread.sleep(1000);
        }

        /* 
         * ThreadB starts, it will try to do lots of updates and deletions 
         * to make sure those log cleaning will happen and the second log 
         * file will be deleted.
         */
        CleanerThread threadB = new CleanerThread("thread2", env, firstDb);
        threadB.start();

        /* Finish the threads. */
        threadA.finishTest();
        threadB.finishTest();

        /* Make sure there is a file deleted. */
        assertTrue(threadB.getCleanedLogFile() >= 1);

        /*
         * Start reading the log changes, if we don't do synchronization 
         * between SyncDataSet life cycle and cleaner, we'll read log entries
         * from an deleted log file, which results in an 
         * EnvironmentFailureException.
         */
        try {
            LogChangeReader reader = new LogChangeReader
                (env, firstDbName, firstProcessor, false, 0);
            Iterator<ChangeTxn> txns = reader.getChangeTxns();
            while (txns.hasNext()) {
                txns.next();
            } 
        } catch (EnvironmentFailureException e) {
            fail("unexpected exception: " + e);
        }
    }

    /*
     * Test the following case:
     * 1. Update the SyncDataSet with the minSyncStart, and see whether cleaner
     *    gets the expected barrier file.
     * 2. Update the SyncDataSet doesn't have the minSyncStart, and see whether
     *    cleaner gets the expected barrier file.
     */
    @Test
    public void testUpdateDataSetAndCleanerConcurrency()
        throws Throwable {

        SyncProcessor processor = new MyMobileSyncProcessor
            (env, "processor", new MobileConnectionConfig());

        addDataSet(new String[] { firstDbName, secDbName }, processor);

        doWork(1, 100, oldValue, false, env, firstDb);
        doWork(1, 100, oldValue, false, env, secDb);

        envImpl.forceLogFileFlip();

        long oldMinSyncStart = 
            envImpl.getSyncCleanerBarrier().getMinSyncStart();

        /* 
         * Update the SyncDataSet that doesn't change the minSyncStart,
         * it won't fail no matter synchronization provided or not. 
         */
        CountDownLatch awaitLatch = new CountDownLatch(1);
        UpdateTestThread threadA = new UpdateTestThread
            ("thread1", env, processor, secDbName, true, awaitLatch, 10);
        long startTime = System.currentTimeMillis();
        threadA.start();

        if (!threadA.isInvoked()) {
            Thread.sleep(100);
        }

        long newMinSyncStart =
            envImpl.getSyncCleanerBarrier().getMinSyncStart();
        assertTrue(newMinSyncStart == oldMinSyncStart);

        /*
         * Min ms expected for work to occur.  Earlier this was 10s and would
         * cause failures in the assertions below from time to time.
         */
        final long timeToDoWork = 5000;

        /* 
         * This check would fail if no synchronization provided, since the main
         * thread needn't to wait for the lock.
         */
        assertTrue((System.currentTimeMillis() - startTime) >= timeToDoWork);
        threadA.finishTest();

        /* Update the SyncDataSet that changes the minSyncStart. */
        awaitLatch = new CountDownLatch(1);
        threadA = new UpdateTestThread
            ("thread2", env, processor, firstDbName, true, awaitLatch, 10);
        threadA.start();

        if (!threadA.isInvoked()) {
            Thread.sleep(100);
        }

        /* 
         * This check will fail if no synchronization provided, but that is OK,
         * which means the barrier file doesn't advance, so the deletion is
         * safe.
         */
        assertTrue(envImpl.getSyncCleanerBarrier().getMinSyncStart() >
                   newMinSyncStart);
        assertTrue(System.currentTimeMillis() - startTime >= timeToDoWork);
        threadA.finishTest();
    }

    /*
     * Test following cases:
     * 1. Remove the only SyncDataSet in the SyncCleanerBarrier, and see 
     *    whetehr cleaner gets the expected barrier file.
     * 2. Remove the SyncDataSet with the minSyncStart, and see whether cleaner
     *    gets the expected barrier file.
     * 3. Remove the SyncDataSet that doesn't have the minSyncStart, and see
     *    whether cleaner gets the expected barrier file.
     */
    @Test
    public void testRemoveDataSetAndCleanerConcurrency()
        throws Throwable {

        SyncProcessor processor = new MyMobileSyncProcessor
            (env, "processor", new MobileConnectionConfig());

        /* Test removing the only SyncDataSet in the SyncCleanerBarrier. */
        addDataSet(new String[] { firstDbName }, processor);
        CountDownLatch awaitLatch = new CountDownLatch(1);
        RemoveTestThread threadA = new RemoveTestThread
            ("thread1", processor, firstDbName, true, awaitLatch, 10);
        threadA.start();
        
        if (!threadA.isInvoked()) {
            Thread.sleep(100);
        }

        /* This check would fail if no synchronization provided. */
        assertTrue(envImpl.getSyncCleanerBarrier().getMinSyncStart() ==
                   LogChangeSet.NULL_POSITION);
        threadA.finishTest();

        addDataSet
            (new String[] { firstDbName, secDbName, thirdDbName }, processor);
        long oldMinSyncStart = 
            envImpl.getSyncCleanerBarrier().getMinSyncStart();

        /* Test removing the SyncDataSet with the minSyncStart. */
        awaitLatch = new CountDownLatch(1);
        threadA = new RemoveTestThread
            ("thread2", processor, firstDbName, true, awaitLatch, 10);
        threadA.start();

        if (!threadA.isInvoked()) {
            Thread.sleep(100);
        }

        /* This check would fail if no synchronization provided. */
        assertTrue(envImpl.getSyncCleanerBarrier().getMinSyncStart() >
                   oldMinSyncStart);
        threadA.finishTest();

        /* Test removing the SyncDataSet that doesn't have the minSyncStart. */
        oldMinSyncStart = envImpl.getSyncCleanerBarrier().getMinSyncStart();
        awaitLatch = new CountDownLatch(1);
        threadA = new RemoveTestThread
            ("thread3", processor, thirdDbName, true, awaitLatch, 10);
        long startTime = System.currentTimeMillis();
        threadA.start();

        if (!threadA.isInvoked()) {
            Thread.sleep(100);
        }

        assertTrue(envImpl.getSyncCleanerBarrier().getMinSyncStart() ==
                   oldMinSyncStart);

        /*
         * This check would fail if no synchronization provided, since the main
         * thread doesn't need to wait for the lock.
         */
        assertTrue((System.currentTimeMillis() - startTime) >= 10000);
        threadA.finishTest();
    }

    private void addDataSet(String[] dbNames, SyncProcessor processor) {
        for (String dbName : dbNames) {
            ArrayList<SyncDatabase> syncDbList = new ArrayList<SyncDatabase>();
            SyncDatabase syncDb =
                new SyncDatabase("ex-" + dbName, dbName, null);
            syncDbList.add(syncDb);
            processor.addDataSet(dbName, syncDbList);
        }
    }

    private static void doWork(int beginIndex, 
                               int endIndex, 
                               String newData,
                               boolean delete,
                               Environment env,
                               Database db) {
        /* Make the transaction write sync. */
        TransactionConfig txnConfig = new TransactionConfig();
        txnConfig.setSync(true);
        Transaction txn = env.beginTransaction(null, txnConfig);

        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        for (int i = beginIndex; i <= endIndex; i++) {
            IntegerBinding.intToEntry(i, key);
            StringBinding.stringToEntry(newData, data);
            if (delete) {
                db.delete(txn, key);
            } else {
                db.put(txn, key, data);
            }
        }
        txn.commit();
    }

    /* Super class for all the testing threads used in this test. */
    public static class CleanerThread extends JUnitThread {
        private final Environment env;
        private final Database db;
        private int cleanedFileNumber;
        private boolean failed = false;

        public CleanerThread(String threadName, Environment env, Database db) {
            super(threadName);
            this.env = env;
            this.db = db;
        }

        @Override
        public void testBody() {

            /* 
             * Do a log file flip, so that the second log file only has one 
             * trace log entry, so that it can be deleted during log 
             * cleaning. 
             */
            DbInternal.getEnvironmentImpl(env).forceLogFileFlip();

            /* Insert data. */
            for (int i = 1; i <= 10; i++) {
                doWork((i - 1) * 100, i * 100, oldValue, false, env, db);
            }

            DbInternal.getEnvironmentImpl(env).forceLogFileFlip();

            /* Update data. */
            for (int i = 1; i <= 10; i++) {
                doWork((i - 1) * 100, i * 100, newValue, false, env, db);
            }

            DbInternal.getEnvironmentImpl(env).forceLogFileFlip();

            /* Delete data. */
            for (int i = 1; i <= 10; i++) {
                doWork((i - 1) * 100, i * 100, null, true, env, db);
            }

            DbInternal.getEnvironmentImpl(env).forceLogFileFlip();

            CheckpointConfig ckptConfig = new CheckpointConfig();
            ckptConfig.setForce(true);

            /* Invoking cleaner and checkpointer. */
            cleanedFileNumber = env.cleanLog();
            env.checkpoint(ckptConfig);
        }

        public int getCleanedLogFile() {
            return cleanedFileNumber;
        }
    }
}
