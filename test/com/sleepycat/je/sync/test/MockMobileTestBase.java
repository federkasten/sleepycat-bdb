package com.sleepycat.je.sync.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;

import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.Environment;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.sync.ExportConfig;
import com.sleepycat.je.sync.ImportConfig;
import com.sleepycat.je.sync.RecordConverter;
import com.sleepycat.je.sync.SyncDatabase;
import com.sleepycat.je.sync.impl.LogChangeReader;
import com.sleepycat.je.sync.mobile.MobileConnectionConfig;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

public abstract class MockMobileTestBase extends TestBase {

    protected static final String dbAName = "db1";
    protected static final String dbBName = "db2";
    protected static final String oldValue = "abcdefghijklmnopqrstuvwxyz";
    protected static final String newValue = oldValue + "0123456789";
    protected static final String procName = "processor";

    protected final File envHome;
    protected Environment env;
    protected Database dbA;
    protected Database dbB;

    public MockMobileTestBase() {
        envHome = SharedTestUtils.getTestDir();
    }

    protected abstract void createEnvAndDbs() throws Exception;

    protected abstract void doLogFileFlip() throws Exception; 

    protected abstract void closeEnvAndDbs();

    protected abstract void doCommitAndAbortCheck
        (TestMobileSyncProcessor processor, 
         LogChangeReader reader) throws Exception;

    @Test
    public void testBasic()
        throws Exception {

        try {
            /* Create the Environment and databases. */
            createEnvAndDbs();

            TestMobileSyncProcessor processor = createProcessor(procName, env);
            addDataSet(processor, new String[] { dbAName, dbBName });

            /* Insert 100 entries. */
            doDbOperations(dbA, 1, 100, oldValue, false);
            doDbOperations(dbB, 1, 100, oldValue, false);

            /* Do updates on the first 20 entries. */
            doDbOperations(dbA, 1, 20, newValue, false);
            doDbOperations(dbB, 21, 40, newValue, false);

            /* Delete entries from 81 to 90. */
            doDbOperations(dbA, 81, 90, null, true);
            doDbOperations(dbB, 81, 90, null, true);

            /* 
             * Log some transactions that making changes across multiple 
             * SyncDataSets. 
             */
            ArrayList<Database> dbList = new ArrayList<Database>();
            dbList.add(dbA);
            dbList.add(dbB);

            doDbOperationsInMultiDbs(dbList, 101, 120, oldValue, false);
            doDbOperationsInMultiDbs(dbList, 101, 110, newValue, false);
            doDbOperationsInMultiDbs(dbList, 111, 120, null, true);

            /* 
             * Force a log file flip, so that we can read all the log 
             * changes. 
             */
            doLogFileFlip();

            /* Create the ExportConfig and ImportConfig, and do a sync. */
            ExportConfig exportConfig = new ExportConfig();
            ImportConfig importConfig = new ImportConfig();

            processor.syncAll(exportConfig, importConfig);

            /* 
             * Do the check after the sync that the contents of local databases 
             * have been updated. 
             */
            doImportCheckAfterSync(dbA, 1, 20);
            doImportCheckAfterSync(dbB, 21, 40);

            /* Assert the exported records number is expected. */
            assertEquals(processor.getExportedRecords(), 340);
        } finally {
            closeEnvAndDbs();
        }
    }

    /*
     * Test the behaviors when the transaction that used to discardChanges
     * commits and aborts, also test the MobileSyncProcessor.removeDataSet
     * behaviors.
     */
    @Test
    public void testDiscardChangeTxnCommitAndAbort()
        throws Exception {

        try {
            /* Create the Environment and databases. */
            createEnvAndDbs();

            /* Create a processor. */
            TestMobileSyncProcessor processor = createProcessor(procName, env);
            addDataSet(processor, new String[] { dbAName });

            LogChangeReader reader = 
                new LogChangeReader(env, dbAName, processor, false, 0);

            /* Add exporting transaction logs. */
            doDbOperations(dbA, 1, 100, oldValue, false);
            doDbOperations(dbA, 21, 30, newValue, false);

            /* Force a log file flip. */
            doLogFileFlip();

            doCommitAndAbortCheck(processor, reader);
        } finally {
            closeEnvAndDbs();
        }
    }

    public static void addDataSet(TestMobileSyncProcessor processor, 
                                  String[] dbNames) { 
        /* Create the SyncDataSets, according the dbNames length. */
        MyRecordConverter converter = new MyRecordConverter();
        converter.initializeConverter
            (new Class[] { Integer.class, String.class },
             new String[] { "key", "data" });

        int counter = 1;
        for (String dbName : dbNames) {
            ArrayList<SyncDatabase> syncDbList = new ArrayList<SyncDatabase>();
            SyncDatabase syncDb = 
                new SyncDatabase("ex-" + dbName, dbName, converter);
            syncDbList.add(syncDb);
            processor.addDataSet(dbName, syncDbList);
            counter++;
        }

        /* Set the Metadata for the SyncProcessor. */
        MockMobile.Metadata metadata = new MockMobile.Metadata();
        for (int i = 1; i <= dbNames.length; i++) {
            MockMobile.Metadata.Publication pub =
                new MockMobile.Metadata.Publication(i, dbNames[i - 1]);
            MockMobile.Metadata.Snapshot snapshot = 
                new MockMobile.Metadata.Snapshot(i, 
                                                 "ex-" + dbNames[i - 1], 
                                                 pub);
            pub.addSnapshot(snapshot);
            metadata.addPublication(pub);
        }
        processor.setMetadata(metadata);
    }

    public static TestMobileSyncProcessor createProcessor(String name,
                                                          Environment env) {
        TestMobileSyncProcessor processor = 
            new TestMobileSyncProcessor(env, name,
                                        new MobileConnectionConfig(), 
                                        new char[] { 'p', 'a', 's', 's' });
        return processor;
    }

    protected void doDbOperations(Database db, 
                                  int beginKey, 
                                  int endKey, 
                                  String value, 
                                  boolean delete) {
        Transaction txn = env.beginTransaction(null, null);
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        for (int i = beginKey; i <= endKey; i++) {
            IntegerBinding.intToEntry(i, key);
            if (delete) {
                db.delete(txn, key);
            } else {
                StringBinding.stringToEntry(value, data);
                db.put(txn, key, data);
            }
        }
        txn.commit();
    }

    private void doDbOperationsInMultiDbs(ArrayList<Database> dbList,
                                          int beginKey,
                                          int endKey,
                                          String value,
                                          boolean delete) {
        Transaction txn = env.beginTransaction(null, null);
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        for (Database db : dbList) {
            for (int i = beginKey; i <= endKey; i++) {
                IntegerBinding.intToEntry(i, key);
                if (delete) {
                    db.delete(txn, key);
                } else {
                    StringBinding.stringToEntry(value, data);
                    db.put(txn, key, data);
                }
            }
        }
        txn.commit();
    }

    /*
     * From the imported data in MockMobile.ServerData, the record between 
     * beginKey and endKey must be deleted or updated. If the OperationStatus
     * is NOTFOUND, which means the record is deleted, if it's success, we're
     * sure that the data has been updated, not coco as we updated before.
     */
    private void doImportCheckAfterSync(Database db, 
                                        int beginKey, 
                                        int endKey) {
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();

        Transaction txn = env.beginTransaction(null, null);
        long counter = 0;
        for (int i = beginKey; i <= endKey; i++) {
            IntegerBinding.intToEntry(i, key);
            OperationStatus status = db.get(txn, key, data, null);
            if (status != OperationStatus.NOTFOUND) {
                assertTrue(!StringBinding.entryToString(data).equals("coco"));
            } else {
                counter++;
            }
        }
        txn.commit();
        assertEquals(db.count(), 100 - counter);
    }

    /* 
     * The RecordConverter used in the sync processor.
     *
     * Here we assume the key/data are the only two columns used in the remote 
     * server, and we already know the type of the columns: Integer and String as
     * used in the above programme.
     */
    static class MyRecordConverter implements RecordConverter {
        private Class[] fieldTypes;
        private String[] fieldNames;

        public void initializeConverter(Class[] externalFieldTypes,
                                        String[] externalFieldNames) {
            this.fieldTypes = externalFieldTypes;
            this.fieldNames = externalFieldNames;
        }

        public void convertLocalToExternal(DatabaseEntry localKey,
                                           DatabaseEntry localData,
                                           Object[] externalFieldValues) {
            externalFieldValues[0] = IntegerBinding.entryToInt(localKey);
            if (localData == null) {
                return;
            }
            externalFieldValues[1] = StringBinding.entryToString(localData);
        }

        public void convertExternalToLocal(Object[] externalFieldValues,
                                           DatabaseEntry localKey,
                                           DatabaseEntry localData) {
            int key = (Integer) fieldTypes[0].cast(externalFieldValues[0]);
            IntegerBinding.intToEntry(key, localKey);
            if (externalFieldValues.length == 1) {
                return;
            }
            String data = (String) fieldTypes[1].cast(externalFieldValues[1]);
            StringBinding.stringToEntry(data, localData);
        }

        public Class[] getExternalFieldTypes() {
            return fieldTypes;
        }

        public String[] getExternalFieldNames() {
            return fieldNames;
        }
    }
}
