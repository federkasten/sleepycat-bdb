package com.sleepycat.je.sync.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.junit.JUnitThread;
import com.sleepycat.je.sync.ChangeReader.ChangeTxn;
import com.sleepycat.je.sync.SyncDatabase;
import com.sleepycat.je.sync.SyncProcessor;
import com.sleepycat.je.sync.impl.SyncDB.DataType;
import com.sleepycat.je.sync.impl.SyncDataSetTest.MyMobileSyncProcessor;
import com.sleepycat.je.sync.mobile.MobileConnectionConfig;
import com.sleepycat.je.utilint.TestHook;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/*
 * Test the concurrency of SyncDataSet life cycle. 
 */
public class ConcurrentSyncDataSetTest extends TestBase {
    private final static String firstDBName = "firstDB";
    private final static String secDBName = "secDB";
    private final String oldValue = "herococo";
    private final File envHome;
    private Environment env;
    private EnvironmentImpl envImpl;
    private Database firstDb;
    private Database secDb;

    public ConcurrentSyncDataSetTest() {
        envHome = SharedTestUtils.getTestDir();
    }

    @Before
    public void setUp() 
        throws Exception {

        super.setUp();
        File realEnvHome = new File(envHome, "ConcurrentSyncDataSetTest");
        deleteDir(realEnvHome);
        realEnvHome.mkdir();
        copyJEProperties(realEnvHome);

        /* Create the Environment. */
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);
        
        env = new Environment(realEnvHome, envConfig);
        envImpl = DbInternal.getEnvironmentImpl(env);
        firstDb = createDb(firstDBName);
        secDb = createDb(secDBName);
    }

    private void deleteDir(File realEnvHome) 
        throws Exception {

        if (realEnvHome.exists()) {
            File[] files = realEnvHome.listFiles();
            for (File file : files) {
                assertTrue(file.isFile());
                file.delete();
            }
            realEnvHome.delete();
        }
    }

    private void copyJEProperties(File toDir)
        throws Exception {

        File jeProperties = new File(envHome, "je.properties");
        int len = (int) jeProperties.length();
        byte[] data = new byte[len];
        FileInputStream fis = null;
        FileOutputStream fos = null;
        try {
            fis = new FileInputStream(jeProperties);
            fos = new FileOutputStream(new File(toDir, "je.properties"));
            fis.read(data);
            fos.write(data);
        } finally {
            if (fis != null) {
                fis.close();
            }

            if (fos != null) {
                fos.close();
            }
        }
    }

    private void closeEnvAndDbs() {
        if (firstDb != null) {
            firstDb.close();
        }

        if (secDb != null) {
            secDb.close();
        }

        if (env != null) {
            env.close();
        }
    }

    /* 
     * Test the following case won't happen:
     * 1. Thread A tries to add a new SyncDataSet, the current endOfLog is 200,
     *    but it sleeps between getting the endOfLog and writing it to the 
     *    SyncCleanerBarrier.
     * 2. Thread B wakes up, and because some log entries logged onto the log, 
     *    the current endOfLog increases to 220, and thread B finishes the 
     *    whole process, so the current minSyncStart for the env is 220, then
     *    it sleeps.
     * 3. Thread A wakes up, it will get an EnvironmentFailureException because
     *    its current nextSyncStart is smaller than the minSyncStart if we 
     *    don't do synchronization.
     */
    @Test
    public void testAddAndAddConcurency()
        throws Throwable {

        SyncProcessor firstProcessor = new MyMobileSyncProcessor
            (env, "test1", new MobileConnectionConfig()); 
        SyncProcessor secProcessor = new MyMobileSyncProcessor
            (env, "test2", new MobileConnectionConfig());

        /* 
         * ThreadA will start first and wait 20 seconds to see whether
         * ThreadB will finish addDataSet before it. 
         */ 
        CountDownLatch awaitLatch = new CountDownLatch(1);
        AddTestThread threadA = new AddTestThread
            ("thread1", firstProcessor, firstDBName, true, awaitLatch, 20);
        threadA.start();

        /* Make sure the endOfLog has been got. */
        while (!threadA.isInvoked()) {
            Thread.sleep(1000);
        }

        /* Do some work to increase the end of the log. */
        doWork(1, 10, oldValue, false, firstDb);
        DbInternal.getEnvironmentImpl(env).forceLogFileFlip();

        /* 
         * ThreadB starts, if no synchronization provided, it will finish
         * addDataSet quicker, thus nextSyncStart of ThreadA will be smaller
         * than the minSyncStart, which results in an 
         * EnvironmentFailureException.
         */
        TestThread threadB = new AddTestThread
            ("thread2", secProcessor, secDBName, false, awaitLatch, 20);
        threadB.start();

        /* Finish the threads. */
        threadA.finishTest();
        threadB.finishTest();

        /* Do the check, expect all threads finish successfully. */
        assertFalse(threadA.isFailed());
        assertFalse(threadB.isFailed());
        String firstKey = 
            SyncDB.generateKey("test1", firstDBName, DataType.CHANGE_SET);
        assertEquals(envImpl.getSyncCleanerBarrier().getMinSyncStart(),
                     envImpl.getSyncCleanerBarrier().getSyncStart(firstKey));

        closeEnvAndDbs();
    }

    /*
     * Test that the following case won't happen:
     * 1. Thread A will add a new SyncDataSet, suppose the current endOfLog is
     *    200, the current minSyncStart is 150, and thread A sleeps between
     *    getting the endOfLog and write it to SyncCleanerBarrier.
     * 2. Thread B wakes up, it invokes discardChanges on its own 
     *    SyncProcessor, and because of that, the minSyncStart changes to 220,
     *    then it sleeps.
     * 3. Thread A wakes up, and it tries to write the endOfLog 200 to the 
     *    SyncCleanerBarrier, we'll get an EnvironmentFailureException since
     *    it's smaller than the current minSyncStart if we don't do 
     *    synchronization.
     */
    @Test
    public void testAddAndUpdateConcurrency() 
        throws Throwable {

        /* Create the processor which will do SyncDataSet updates. */
        SyncProcessor firstProcessor = 
            createProcessor(new String[] { firstDBName }, "test1");

        /* 
         * Do some work to make the nextSyncStart of SyncDataSet in another 
         * SyncProcessor be larger than the current minSyncStart. 
         */
        doWork(1, 100, oldValue, false, firstDb);
        DbInternal.getEnvironmentImpl(env).forceLogFileFlip();

        /* Create another SyncProcessor that will addDataSet. */
        SyncProcessor secProcessor = new MyMobileSyncProcessor
            (env, "test2", new MobileConnectionConfig());
        CountDownLatch awaitLatch = new CountDownLatch(1);

        /*
         * Start the SyncProcessor that will call addDataSet, but it will sleep
         * 20 seconds between finding the endOfLog and write it to 
         * SyncCleanerBarrier, so that the SyncProcessor updates the 
         * minSyncStart can work.
         */
        AddTestThread threadB = new AddTestThread
            ("thread2", secProcessor, secDBName, true, awaitLatch, 20);
        threadB.start();

        /* Make sure the endOfLog has been got. */
        while (!threadB.isInvoked()) {
            Thread.sleep(1000);
        }

        /* 
         * Add some transactional log, so that minSyncStart can change if we 
         * call LogChangeReader.discardChanges.
         */
        for (int i = 2; i <= 10; i++) {
            doWork((i - 1) * 100 + 1, i * 100, oldValue, false, firstDb);
        }
        DbInternal.getEnvironmentImpl(env).forceLogFileFlip();

        /*
         * Start the thread that updates the minSyncStart, it'll update the
         * minSyncStart larger than the nextSyncStart of the newly added 
         * SyncDataset if there is no synchronization.
         */
        UpdateTestThread threadA = new UpdateTestThread("thread1", 
                                                        env, 
                                                        firstProcessor, 
                                                        firstDBName, 
                                                        false, 
                                                        awaitLatch, 
                                                        20);
        threadA.start();

        /* Finish the threads. */
        threadA.finishTest();
        threadB.finishTest();

        /* Check that no thread fails. */
        assertFalse(threadA.isFailed());
        assertFalse(threadB.isFailed());

        closeEnvAndDbs();
    }

    /*
     * Test the following case won't happen:
     * 1. Thread A starts a SyncProcessor, and it tries to add a SyncDataSet,
     *    suppose the current endOfLog is 200, and minSyncStart is 100, but it 
     *    sleeps after getting endOfLog.
     * 2. Thread B wakes up, it tries to remove a SyncDataSet that has the
     *    minSyncStart, and the current minSyncStart changes to 220, thread B
     *    sleeps after this is done.
     * 3. Thread A wakes up, it continues adding a new SyncDataSet to the 
     *    SyncCleanerBarrier, but it becomes smaller than the current 
     *    minSyncStart 220.
     */
    @Test
    public void testAddAndRemoveConcurrency()
        throws Throwable {

        /*
         * Create another database, so that the first SyncProcessor has two
         * SyncDataSets.
         */
        final String thirdDBName = "thirdDB";
        Database thirdDb = createDb(thirdDBName);

        /* 
         * Add the first SyncDataSet, which makes the minSyncStart is the 
         * current endOfLog.
         */
        SyncProcessor firstProcessor = new MyMobileSyncProcessor
            (env, "test1", new MobileConnectionConfig());
        addSyncDataSet(firstProcessor, firstDBName);

        /* Do some work to increase the endOfLog. */
        doWork(1, 100, oldValue, false, firstDb);
        DbInternal.getEnvironmentImpl(env).forceLogFileFlip();

        /* 
         * The SyncProcessor that adds a new SyncDataSet starts, but it will 
         * sleep between geting current endOfLog and write it to the 
         * SyncCleanerBarrier.
         */
        SyncProcessor secProcessor = new MyMobileSyncProcessor
            (env, "test2", new MobileConnectionConfig());
        CountDownLatch awaitLatch = new CountDownLatch(1);
        AddTestThread threadB = new AddTestThread
            ("thread2", secProcessor, secDBName, true, awaitLatch, 20);
        threadB.start();

        /* Make sure the endOfLog has been got. */
        while (!threadB.isInvoked()) {
            Thread.sleep(1000);
        }

        /* Do some work to increase the current endOfLog. */
        doWork(101, 200, oldValue, false, firstDb);
        DbInternal.getEnvironmentImpl(env).forceLogFileFlip();

        /* 
         * Add another new SyncDataSet to the SyncProcessor that will do 
         * removeDataSet, note the nextSyncStart of this new SyncDataSet is 
         * larger than the former endOfLog.
         */
        addSyncDataSet(firstProcessor, thirdDBName);

        /* Start the SyncProcessor that will do removeDataSet. */
        RemoveTestThread threadA = new RemoveTestThread
            ("thread1", firstProcessor, firstDBName, false, awaitLatch, 20);
        threadA.start();

        /* Finish the threads. */
        threadA.finishTest();
        threadB.finishTest();

        /* Check that no threads would fail. */
        assertFalse(threadA.isFailed());
        assertFalse(threadB.isFailed());

        thirdDb.close();

        closeEnvAndDbs();
    }

    /*
     * Test the following case won't happen:
     * 1. Thread A tries to update a SyncDataSet, but it sleeps before it does
     *    write to the SyncClenaerBarrier.
     * 2. Thread B wakes up, it tries to remove that SyncDataSet, and it 
     *    finishes correctly.
     * 3. Thread A wakes up, it continues writing the new nextSyncStart to the
     *    SyncCleanerBarrier, but we treat it as a newly added SyncDataSet.
     */
    @Test
    public void testUpdateAndRemoveConcurrency()
        throws Throwable {

        SyncProcessor processor = 
            createProcessor(new String[] { firstDBName }, "test");

        doWork(1, 100, oldValue, false, firstDb);

        envImpl.forceLogFileFlip();

        CountDownLatch awaitLatch = new CountDownLatch(1);
        UpdateTestThread threadA = new UpdateTestThread
            ("thread1", env, processor, firstDBName, true, awaitLatch, 20);
        threadA.start();

        Thread.sleep(1000);

        RemoveTestThread threadB = new RemoveTestThread
            ("thread2", processor, firstDBName, false, awaitLatch, 20);
        threadB.start();

        threadA.finishTest();
        threadB.finishTest();

        assertTrue(envImpl.getSyncCleanerBarrier().getMinSyncStart() ==
                   LogChangeSet.NULL_POSITION);

        closeEnvAndDbs();
    }

    private SyncProcessor createProcessor(String[] dbNames, 
                                          String processorName) {
        SyncProcessor processor = new MyMobileSyncProcessor
            (env, processorName, new MobileConnectionConfig());
        if (dbNames != null) {
            for (String dbName : dbNames) {
                addSyncDataSet(processor, dbName);
            }
        }

        return processor;
    }

    /* Add a SyncDataSet to a SyncProcessor. */
    private static void addSyncDataSet(SyncProcessor processor, 
                                       String dbName) {
        ArrayList<SyncDatabase> syncDbList = new ArrayList<SyncDatabase>();
        syncDbList.add(new SyncDatabase("ex-" + dbName, dbName, null));
        processor.addDataSet(dbName, syncDbList);
    }

    private Database createDb(String dbName) 
        throws Exception { 

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setTransactional(true);

        return env.openDatabase(null, dbName, dbConfig);
    }

    private void doWork(int beginIndex, 
                        int endIndex, 
                        String newData,
                        boolean delete,
                        Database db) {
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        Transaction txn = env.beginTransaction(null, null);
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

    public static class MyTestHook implements TestHook {
        private final CountDownLatch awaitLatch;
        private final long waitSeconds;
        private boolean invoked = false;

        public MyTestHook(CountDownLatch awaitLatch, long waitSeconds) {
            this.awaitLatch = awaitLatch;
            this.waitSeconds = waitSeconds;
        }

        public void doHook() {
            try {
                invoked = true;
                awaitLatch.await(waitSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                /* Should never happen. */
            }
        }

        public void hookSetup() {
            throw new UnsupportedOperationException("Unsupported"); 
        }

        public void doIOHook() {
            throw new UnsupportedOperationException("Unsupported");
        }

        public Thread getHookValue() {
            throw new UnsupportedOperationException("Unsupported");
        }

        public boolean isInvoked() {
            return invoked;
        }

	public void doHook(Object obj) {
            throw new UnsupportedOperationException("Unsupported");
	}
    }

    /* Super class for all the testing threads used in this test. */
    public static abstract class TestThread extends JUnitThread {
        protected final SyncProcessor processor;
        protected final String dbName;
        protected final boolean enableHook;
        protected final CountDownLatch awaitLatch;
        protected final long waitSeconds;
        protected final MyTestHook testHook;
        protected boolean failure = false;

        public TestThread(String threadName,
                          SyncProcessor processor,
                          String dbName,
                          boolean enableHook,
                          CountDownLatch awaitLatch,
                          long waitSeconds) {
            super(threadName);
            this.processor = processor;
            this.dbName = dbName;
            this.enableHook = enableHook;
            this.awaitLatch = awaitLatch;
            this.waitSeconds = waitSeconds;
            this.testHook = new MyTestHook(awaitLatch, waitSeconds);
        }

        @Override
        public void testBody() {
            doThreadWork();

            if (!enableHook && awaitLatch.getCount() != 0) {
                awaitLatch.countDown();
            }
        }

        protected abstract void doThreadWork();

        public boolean isFailed() {
            return failure;
        }

        public boolean isInvoked() {
            return testHook.isInvoked();
        }
    }

    public static class AddTestThread extends TestThread {
        public AddTestThread(String threadName,
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
                addSyncDataSet(processor, dbName);
            } catch (EnvironmentFailureException e) {
                failure = true;
                throw e;
            }
        }
    }

    public static class UpdateTestThread extends TestThread {
        private final Environment env;

        public UpdateTestThread(String threadName,
                                Environment env,
                                SyncProcessor processor,
                                String dbName,
                                boolean enableHook,
                                CountDownLatch awaitLatch,
                                long waitSeconds) {
            super(threadName, processor, dbName, 
                  enableHook, awaitLatch, waitSeconds);
            this.env = env;
        }

        @Override
        protected void doThreadWork() {
            try {
                LogChangeReader reader = 
                    new LogChangeReader(env, dbName, processor, false, 0);

                if (enableHook) {
                    reader.setWaitHook(testHook);
                }

                Iterator<ChangeTxn> txns = reader.getChangeTxns();
               
                while (txns.hasNext()) {
                    txns.next();
                    /* Use the auto-commit transaction. */
                    reader.discardChanges(null);
                }
            } catch (EnvironmentFailureException e) {
                failure = true;
                throw e;
            }
        }
    }

    public static class RemoveTestThread extends TestThread {
        public RemoveTestThread(String threadName,
                                SyncProcessor processor,
                                String dbName,
                                boolean enableHook,
                                CountDownLatch awaitLatch,
                                long waitSeconds) {
            super(threadName, processor, dbName, 
                  enableHook, awaitLatch, waitSeconds);
        }

        @Override
        public void doThreadWork() {
            try {
                if (enableHook) {
                    processor.setRemoveHook(testHook);
                }

                processor.removeDataSet(dbName);
            } catch (EnvironmentFailureException e) {
                failure = true;
                throw e;
            }
        }
    }
}
