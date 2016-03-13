package com.sleepycat.je.sync.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;

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
import com.sleepycat.je.EnvironmentStats;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.sync.ChangeReader.ChangeTxn;
import com.sleepycat.je.sync.SyncDatabase;
import com.sleepycat.je.sync.SyncProcessor;
import com.sleepycat.je.sync.impl.SyncCleanerBarrier.StartInfo;
import com.sleepycat.je.sync.impl.SyncDB.DataType;
import com.sleepycat.je.sync.mobile.MobileConnectionConfig;
import com.sleepycat.je.sync.mobile.MobileSyncProcessor;
import com.sleepycat.je.util.DualTestCase;
import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.util.test.SharedTestUtils;

/*
 * Test the SyncDataSet life cycle. Note this is not the concurrent tests, so
 * all changes to the SyncCleanerBarrier are made in a single thread, we'll 
 * test concurrency safety in the concurrent tests.
 */
public class SyncDataSetTest extends DualTestCase {
    private final static String firstDBName = "firstDB";
    private final static String secDBName = "secDB";
    private final static String processorName = "test";
    private final static String firstKey = 
        SyncDB.generateKey(processorName, firstDBName, DataType.CHANGE_SET);
    private final static String secKey =
        SyncDB.generateKey(processorName, secDBName, DataType.CHANGE_SET);
    private final String value1 = "abcdefghijklmnopqrstuvwxyz";
    private final String value2 = "abcdefghijklmnopqrstuvwxyz0123456789";
    private final File envHome;
    private final CheckpointConfig ckptConfig;
    private final StatsConfig statsConfig;
    private Environment env;
    private EnvironmentImpl envImpl;

    public SyncDataSetTest() {
        envHome = SharedTestUtils.getTestDir();
        ckptConfig = new CheckpointConfig();
        ckptConfig.setForce(true);
        statsConfig = new StatsConfig();
        statsConfig.setFast(true);
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
         * Set smaller log fle size, so that log cleaning can easily happen,
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
            (EnvironmentConfig.CLEANER_MIN_UTILIZATION, "95");

        env = create(envHome, envConfig);
        envImpl = DbInternal.getEnvironmentImpl(env);
    }

    @After
    public void tearDown() 
        throws Exception {

        close(env);
        super.tearDown();
    }

    /*
     * Test that a SyncProcessor without any SyncDataSets won't affect the 
     * cleaner hehaviors.
     */
    @Test
    public void testNoSyncDataSet()
        throws Exception {

        Database db = createDb(firstDBName);

        SyncProcessor processor = createProcessor(null);

        /* Do some work to make some log files cleaned. */
        doWork(new Database[] { db });

        EnvironmentStats envStats = env.getStats(statsConfig);

        /* 
         * Check no log cleaning at the moment, the minSyncStart is
         * LogChangeSet.NULL_POSITION, because no SyncDataSet is added.
         */
        long fileDeletion = envStats.getNCleanerDeletions();
        assertTrue(fileDeletion == 0);
        assertEquals(envImpl.getSyncCleanerBarrier().getMinSyncStart(),
                     LogChangeSet.NULL_POSITION);

        /* Invoke the cleaner and checkpointer. */
        env.cleanLog();
        env.checkpoint(ckptConfig);

        /* 
         * Check that log cleaning happens, but the minSyncStart is still
         * LogChangeSet.NULL_POSITION.
         */
        long newFileDeletion = 
            env.getStats(statsConfig).getNCleanerDeletions();
        assertTrue(newFileDeletion > fileDeletion);
        assertEquals(envImpl.getSyncCleanerBarrier().getMinSyncStart(),
                     LogChangeSet.NULL_POSITION);

        db.close();
    }

    /* 
     * Test that adding a new SyncDataSet affects the ClanerBarrier correctly,
     * it tests following cases:
     * 
     * 1. The new SyncDataSet is the first SyncDataSet of the SyncProcessor.
     * 2. Adding a new SyncDataSet when there exists other SyncDataSets.
     * 3. Adding a new SyncDataSet with a nextSyncStart smaller than the
     *    current minSyncStart would throw an EnvironmentFailureException.
     *
     * We make the first SyncDataSet's nextSyncStart lives on the first log 
     * file, we check that checkpointer doesn't do any file deletion until 
     * that SyncDataSet is removed.
     */
    @Test
    public void testAddSyncDataSet()
        throws Exception {

        Database db = createDb(firstDBName);

        SyncProcessor processor = 
            createProcessor(new String[] { firstDBName });

        /* 
         * Check that the CleanerBarrier file has changed, and it's the same as
         * what we've set for the first SyncDataSet. 
         */
        long firstDBSyncStart = 
            envImpl.getSyncCleanerBarrier().getSyncStart(firstKey);
        assertTrue(envImpl.getSyncCleanerBarrier().getMinSyncStart() == 
                   firstDBSyncStart);

        /* Do some work to make some log files cleaned. */
        doWork(new Database[] { db });

        /* Invoke the log cleaning. */
        env.cleanLog();
        env.checkpoint(ckptConfig);

        /* Current cleaner barrier file is 0, so no log cleaning happens. */
        long fileDeletion =
            env.getStats(statsConfig).getNCleanerDeletions();
        assertTrue(fileDeletion == 0);

        /* 
         * Add a new SyncDataSet when there exists other SyncDataSets, it 
         * should be larger than the original one, and no minSyncStart 
         * updates. 
         */
        Database secDB = createDb(secDBName);
        addSyncDataSet(processor, secDBName);
        long secDBSyncStart = 
            envImpl.getSyncCleanerBarrier().getSyncStart(secKey);
        assertTrue(envImpl.getSyncCleanerBarrier().getMinSyncStart() == 
                   firstDBSyncStart);
        assertTrue(envImpl.getSyncCleanerBarrier().getMinSyncStart() != 
                   secDBSyncStart);

        /* Remove the SyncStart. */
        processor.removeDataSet(firstDBName);
        processor.removeDataSet(secDBName);

        /* Cleaner does work, checkpointer deletes log. */
        env.cleanLog();
        env.checkpoint(ckptConfig);
        assertTrue(env.getStats(statsConfig).getNCleanerDeletions() > 0);

        /*
         * Add a new SyncDataSet with a smaller nextSyncStart would throw an
         * EnvironmentFailureException.
         */
        addSyncDataSet(processor, firstDBName);

        try {
            /* Use the updateSyncStart directly to simulate this case. */
            envImpl.getSyncCleanerBarrier().updateSyncStart
                ("new-test", new StartInfo(DbLsn.makeLsn(10, 200), false));
            fail("Expect to see exceptions here.");
        } catch (EnvironmentFailureException expected) {
            /* Expect to see this exception. */
        }
    }

    /* 
     * Test that updating the nextSyncStarts would affect the minSyncStart
     * correctly, it tests following cases:
     *
     * 1. A SyncDataSet doesn't have the minSyncStart does update.
     * 2. The SyncDataSet has the minSyncStart does update.
     *
     * We make the minSyncStart lives on the first log file, so when we update
     * the SyncDataSet doesn't have the minSyncStart, we expect no log file 
     * deletion, and we expect log file deletion when we update the SyncDataSet
     * that has the minSyncStart.
     */
    @Test
    public void testUpdateSyncStart()
        throws Exception {

        Database firstDB = createDb(firstDBName);
        Database secDB = createDb(secDBName);

        /* Create the SyncProcessor. */
        SyncProcessor processor = 
            createProcessor(new String[] { firstDBName, secDBName });

        /* Get the nextSyncStart for those two SyncDataSets. */
        long firstSyncStart = 
            envImpl.getSyncCleanerBarrier().getSyncStart(firstKey);
        long secSyncStart = 
            envImpl.getSyncCleanerBarrier().getSyncStart(secKey);

        /* Check minSyncStart is the nextSyncStart of the first SyncDataSet. */
        assertTrue(firstSyncStart ==
                   envImpl.getSyncCleanerBarrier().getMinSyncStart());

        /* Do some work to make log files obsolete. */
        doWork(new Database[] { secDB, firstDB });

        envImpl.forceLogFileFlip();

        LogChangeReader firstReader = 
            new LogChangeReader(env, firstDBName, processor, true, 30);
        LogChangeReader secReader =
            new LogChangeReader(env, secDBName, processor, true, 30);

        /* Second SyncDataSet reads the ChangeTxn. */
        discardChanges(secReader);

        /* Get nextSyncStart of second SyncDataSet, it should be changed. */
        long newSecSyncStart = 
            envImpl.getSyncCleanerBarrier().getSyncStart(secKey);
        assertTrue(newSecSyncStart > secSyncStart);
        assertTrue(firstSyncStart ==
                   envImpl.getSyncCleanerBarrier().getMinSyncStart());

        /* Invoke the cleaner and checkpointer, expect no log file deletion. */
        env.cleanLog();
        env.checkpoint(ckptConfig);
        assertTrue(env.getStats(statsConfig).getNCleanerDeletions() == 0);

        /* First SyncDataSet invokes discardChanges. */
        discardChanges(firstReader);

        /* Expect the minSyncStart changes. */
        long newFirstSyncStart =
            envImpl.getSyncCleanerBarrier().getSyncStart(firstKey);
        assertTrue(newFirstSyncStart > newSecSyncStart);
        assertTrue(newSecSyncStart ==
                   envImpl.getSyncCleanerBarrier().getMinSyncStart());

        /* Invoke the log cleaning again, expect file deletions. */
        env.cleanLog();
        env.checkpoint(ckptConfig);
        assertTrue(env.getStats(statsConfig).getNCleanerDeletions() > 0);
                   
        firstDB.close();
        secDB.close();
    }

    private void discardChanges(LogChangeReader changeReader) {
        Iterator<ChangeTxn> txnIterator = changeReader.getChangeTxns();
        while (txnIterator.hasNext()) {
            txnIterator.next();
        }
        Transaction txn = env.beginTransaction(null, null);
        changeReader.discardChanges(txn);
        txn.commit();
    }

    /*
     * Test that removing a SyncDataSet should affect the minSyncStart 
     * correctly, it has following cases:
     *
     * 1. There is only one SyncDataSet in the SyncProcessor, and we remove it.
     * 2. Remove the SyncDataSet that has the minSyncStart.
     * 3. Remove a SyncDataset that doesn't have the minSyncStart.
     */
    @Test
    public void testRemoveSyncDataSet()
        throws Exception {

        Database firstDB = createDb(firstDBName);

        SyncProcessor processor = 
            createProcessor(new String[] { firstDBName });

        long minSyncStart = envImpl.getSyncCleanerBarrier().getMinSyncStart();

        /* 
         * Make sure that the minSyncStart changes to 
         * LogChangeSet.NULL_POSITION after removing the last SyncDataSet.
         */
        processor.removeDataSet(firstDBName);
        assertEquals(envImpl.getSyncCleanerBarrier().getMinSyncStart(),
                     LogChangeSet.NULL_POSITION);
        assertTrue(envImpl.getSyncCleanerBarrier().getMinSyncStart() != 
                   minSyncStart);

        /* Add two SyncDataSets. */
        addSyncDataSet(processor, firstDBName);
        minSyncStart = envImpl.getSyncCleanerBarrier().getMinSyncStart();

        Database secDB = createDb(secDBName);
        addSyncDataSet(processor, secDBName);
        long secSyncStart = 
            envImpl.getSyncCleanerBarrier().getSyncStart(secKey);

        /* 
         * Remove the SyncDataSet that has the minSyncStart, check that 
         * minSyncStart changes to a larger number. 
         */
        processor.removeDataSet(firstDBName);
        assertEquals(secSyncStart, 
                     envImpl.getSyncCleanerBarrier().getMinSyncStart());
        assertTrue(envImpl.getSyncCleanerBarrier().getMinSyncStart() >
                   minSyncStart);

        /* Add a new SyncDataSet, so the list has two SyncDataSets. */
        addSyncDataSet(processor, firstDBName);
        minSyncStart = envImpl.getSyncCleanerBarrier().getMinSyncStart();

        /* 
         * Remove the SyncDataSet that doesn't have the minSyncStart, expect
         * no minSyncStart changes. 
         */
        processor.removeDataSet(firstDBName);
        assertEquals(envImpl.getSyncCleanerBarrier().getMinSyncStart(), 
                     minSyncStart);

        firstDB.close();
        secDB.close();
    }

    @Test
    public void testRemoveSameSyncDataSetMultipleTimes()
        throws Exception {

        Database db = createDb(firstDBName);

        /* 
         * Test the first usage error: adding the same SyncDataSet to the 
         * SyncProcessor multiple times.
         */

        SyncProcessor processor = 
            createProcessor(new String[] { firstDBName });
        /* Expect the IllegalStateException here. */
        try {
            addSyncDataSet(processor, firstDBName);
            fail("expected exceptions here");
        } catch (IllegalStateException e) {
            /* expected exception. */
        }
        /* Make sure there is only one record in the SyncDB. */
        assertTrue(processor.getSyncDB().getCount() == 1);

        /* Make some log entries for the SyncDataSet. */
        doWork(new Database[] { db });
        /* Flip the log file so that they can be read by LogChangeReader. */
        envImpl.forceLogFileFlip();
        
        /*
         * Test the second usage error: updating the metadata for a SyncDataSet
         * that has already been removed. 
         */

        LogChangeReader reader = 
            new LogChangeReader(env, firstDBName, processor, false, 0);
        Iterator<ChangeTxn> txns = reader.getChangeTxns();
        /* Remove a SyncDataSet. */
        processor.removeDataSet(firstDBName);
        /* Make sure the removal is successfully finished. */
        assertTrue(processor.getSyncDB().getCount() == 0);
        /* Expect IllegalStateException here. */
        try {
            while (txns.hasNext()) {
                txns.next();
                reader.discardChanges(null);
            }             
            fail("expected exceptions here");
        } catch (IllegalStateException e) {
            /* expected exceptions. */
        }

        /* Test the third usage error: remove a SyncDataSet multiple times. */
        try {
            processor.removeDataSet(firstDBName);
            fail("execpted exceptions here");
        } catch (IllegalStateException e) {
            /* expected exceptions. */
        }

        db.close();
    }

    private SyncProcessor createProcessor(String[] dbNames) {
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
    private void addSyncDataSet(SyncProcessor processor, String dbName) {
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

    /* Write data and make data obsolete. */
    private void doWork(Database[] dbs)
        throws Exception {

        for (Database db : dbs) {
            for (int i = 1; i <= 50; i++) {
                doWork((i - 1) * 100 + 1, i * 100, value1, false, db);
            }

            for (int i = 1; i <= 50; i++) {
                doWork((i - 1) * 100 + 1, i * 100, value2, false, db);
            }

            for (int i = 1; i <= 50; i++) {
                doWork((i - 1) * 100 + 1, i * 100, null, true, db);
            }
        }
    }

    private void doWork(int beginIndex, 
                        int endIndex, 
                        String newData,
                        boolean delete,
                        Database db) 
        throws Exception {

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

    public static class MyMobileSyncProcessor extends MobileSyncProcessor {
        public MyMobileSyncProcessor(Environment env,
                                     String processorName,
                                     MobileConnectionConfig connectionConfig) {
            super(env, processorName, connectionConfig);
        }

        @Override
        public void removeDataSet(String dataSetName) {
            getDataSets().remove(dataSetName);
            unregisterDataSet(dataSetName);
        }
    }
}
