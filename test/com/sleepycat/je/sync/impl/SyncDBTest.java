package com.sleepycat.je.sync.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Collection;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.sync.ProcessorMetadata;
import com.sleepycat.je.sync.SyncDataSet;
import com.sleepycat.je.sync.SyncDatabase;
import com.sleepycat.je.sync.SyncProcessor;
import com.sleepycat.je.sync.impl.LogChangeSet.LogChangeSetBinding;
import com.sleepycat.je.sync.impl.SyncDB.OpType;
import com.sleepycat.je.sync.mobile.MobileConnectionConfig;
import com.sleepycat.je.sync.mobile.MobileSyncProcessor;
import com.sleepycat.je.util.DualTestCase;
import com.sleepycat.util.test.SharedTestUtils;

public class SyncDBTest extends DualTestCase {
    private static final String dbName = "testDB";
    private final File envHome;
    protected Environment env;

    public SyncDBTest() {
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

        env = create(envHome, envConfig);
    }

    @After
    public void tearDown()
        throws Exception {

        close(env);
        super.tearDown();
    }

    @Test
    public void testBasic() 
        throws Exception {

        try {
            assertEquals(DbInternal.getEnvironmentImpl(env).
                         getSyncCleanerBarrier().getMinSyncStart(), 
                         LogChangeSet.NULL_POSITION);

            SyncProcessor processor = new MobileSyncProcessor
                (env, "processor", new MobileConnectionConfig());

            SyncDB syncDb = processor.getSyncDB();

            /* It doesn't have any contents now. */
            assertTrue(syncDb.getCount() == 0);

            /* Write a new record. */
            LogChangeSet set = new LogChangeSet(100, 100);
            DatabaseEntry data = new DatabaseEntry();
            LogChangeSetBinding binding = new LogChangeSetBinding();
            binding.objectToEntry(set, data);
            Transaction txn = env.beginTransaction(null, null);
            syncDb.writeChangeSetData
                (env, txn, "processor", "test", data, OpType.INSERT);
            txn.commit();

            /* Check the data has been written to the database. */
            assertTrue(syncDb.getCount() == 1);

            /* Read the writen data from SyncDB. */
            DatabaseEntry readData = new DatabaseEntry();
            txn = env.beginTransaction(null, null);
            processor.readChangeSetData(txn, "test", readData);
            LogChangeSet newSet = binding.entryToObject(readData);
            txn.commit();

            /* Check the read data is the same as the written data. */
            assertEquals(newSet.getNextSyncStart(), set.getNextSyncStart());
            assertEquals(newSet.getLastSyncEnd(), set.getLastSyncEnd());

            /* Write the metadata to the database. */
            ProcessorMetadata<MySyncDataSet> metadata = 
                new ProcessorMetadata<MySyncDataSet>();
            metadata.addDataSet(new MySyncDataSet("test1", processor, null));
            metadata.addDataSet(new MySyncDataSet("test2", processor, null));
            txn = env.beginTransaction(null, null);
            processor.writeProcessorMetadata(txn, metadata);
            txn.commit();

            /* Check the metadata has been written to the database. */
            assertTrue(syncDb.getCount() == 2);

            readData = new DatabaseEntry();
            txn = env.beginTransaction(null, null);
            metadata = processor.readProcessorMetadata(txn);
            txn.commit();

            assertTrue(metadata.getDataSets().size() == 2);

            for (SyncDataSet syncDataSet : metadata.getDataSets()) {
                assertTrue(syncDataSet.getName().equals("test1") ||
                           syncDataSet.getName().equals("test2"));
                assertTrue(syncDataSet.getProcessor() == processor);
                assertTrue(syncDataSet.getDatabases() == null);
            }

            /* 
             * Close and reopen the Environment to see there does exist 
             * LogChangeSet information in the map of SyncCleanerBarrier.
             *
             * TODO: We set trigger for the SyncDB, but JE currently can't 
             * recover a trigger, this part of the test has to be disabled
             * until the trigger recovery is done.
             */
            close(env);

            EnvironmentConfig envConfig = new EnvironmentConfig();
            envConfig.setAllowCreate(true);
            envConfig.setTransactional(true);
            env = create(envHome, envConfig);

            /* Assert the data read is equal to what we write. */
            assertEquals(DbInternal.getEnvironmentImpl(env).
                         getSyncCleanerBarrier().getMinSyncStart(), 
                         set.getNextSyncStart());
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    static class MySyncDataSet extends SyncDataSet {
        public MySyncDataSet(String dataSetName,
                             SyncProcessor processor,
                             Collection<SyncDatabase> databases) {
            super(dataSetName, processor, databases);
        }
    }
}
