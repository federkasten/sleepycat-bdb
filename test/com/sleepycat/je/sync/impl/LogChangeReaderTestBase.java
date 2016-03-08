package com.sleepycat.je.sync.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.dbi.DatabaseId;
import com.sleepycat.je.sync.ChangeReader.Change;
import com.sleepycat.je.sync.ChangeReader.ChangeTxn;
import com.sleepycat.je.sync.ChangeReader.ChangeType;
import com.sleepycat.je.sync.SyncDatabase;
import com.sleepycat.je.sync.SyncProcessor;
import com.sleepycat.je.sync.mobile.MobileConnectionConfig;
import com.sleepycat.je.sync.mobile.MobileSyncProcessor;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/*
 * TestBase for standalone and replicated LogChangeReaderTest.
 */
public abstract class LogChangeReaderTestBase extends TestBase {
    private static final String dbName = "testDB";
    private static final String dupDbName = "dupDB";
    protected static final String oldValue = "abcdefghijklmnopqrstuvwxyz";
    protected static final String newValue = oldValue + "0123456789";
    protected final File envHome;
    private int txnCounter = 0;
    protected Environment env;
    protected Database db;
    protected Database dupDb;
    protected Map<Long, MyChangeTxn> expectedTxns;
    private Map<DatabaseId, String> dbIdNames;
    protected LogChangeReader reader;

    public LogChangeReaderTestBase() {
        envHome = SharedTestUtils.getTestDir();
    }

    protected abstract void createEnvironment() throws Exception;

    protected abstract void doLogFileFlip();

    /* Test the LogChangeReader reads the log correctly. */
    protected void createTransactionLog()  
        throws Exception {

        createEnvironment();

        expectedTxns = new HashMap<Long, MyChangeTxn>();

        db = createDb(dbName, false);
        dupDb = createDb(dupDbName, true);

        ArrayList<String> dbNames = new ArrayList<String>();
        dbNames.add(dbName);
        dbNames.add(dupDbName);

        /* Create the expected DatabaseId to database names mapping. */
        dbIdNames = new HashMap<DatabaseId, String>();
        dbIdNames.put(DbInternal.getDatabaseImpl(dupDb).getId(), dupDbName);
        dbIdNames.put(DbInternal.getDatabaseImpl(db).getId(), dbName);

        SyncProcessor processor = createSyncProcessor(dbNames);

        reader = new LogChangeReader(env, "dataset", processor, false, 0);

        /* Do inserts. */
        createTransactionalLog
            (true, db, OpType.INSERT, 1, 200, oldValue, expectedTxns); 

        /* Do updates. */
        createTransactionalLog
            (true, db, OpType.UPDATE, 21, 30, newValue, expectedTxns);

        /* Do deletes. */
        createTransactionalLog
            (true, db, OpType.DELETE, 61, 80, null, expectedTxns);

        /* Do reads, expected no txnal data read. */
        createTransactionalLog
            (true, db, OpType.READ, 1, 60, null, expectedTxns);

        /* Abort a transaction. */
        createTransactionalLog
            (false, db, OpType.DELETE, 1, 60, null, expectedTxns);

        /* A tranaction writes multiple databases. */
        Transaction txn = beginTransaction();
        MyChangeTxn expectedTxn = new MyChangeTxn(txn.getId());
        doDbOperation
            (201, 250, oldValue, db, txn, OpType.INSERT, expectedTxn);
        doDbOperation
            (1, 50, oldValue, dupDb, txn, OpType.INSERT, expectedTxn);
        expectedTxns.put(txn.getId(), expectedTxn);
        txn.commit();

        /* Do updates and deleteson a duplicated database. */
        txn = beginTransaction();
        expectedTxn = new MyChangeTxn(txn.getId());
        /* The OpType is insert, because the dupDb is a duplicated database. */
        doDbOperation(1, 20, newValue, dupDb, txn, OpType.INSERT, expectedTxn);
        doDbOperation(31, 40, null, dupDb, txn, OpType.DELETE, expectedTxn);
        expectedTxns.put(txn.getId(), expectedTxn);
        txn.commit();

        /* 
         * A long transaction commit after two short transaction starts before
         * it commits.
         */
        txn = beginTransaction();
        expectedTxn = new MyChangeTxn(txn.getId());

        doDbOperation(251, 300, oldValue, db, txn, OpType.INSERT, expectedTxn);
        expectedTxns.put(txn.getId(), expectedTxn);

        createTransactionalLog
            (true, db, OpType.INSERT, 301, 320, oldValue, expectedTxns);

        createTransactionalLog
            (true, db, OpType.DELETE, 1, 60, null, expectedTxns);

        createTransactionalLog
            (false, db, OpType.INSERT, 51, 60, oldValue, expectedTxns);

        txn.commit();

        /* Force a log file flip, so that we can read all the log changes. */
        doLogFileFlip();
    }

    protected void doCommonCheck() {

        /* 
         * Check to see whether the id->dbName mappings in LogChangeReader are 
         * the same as we expect. 
         */
        assertTrue(reader.getSyncDbs().size() == dbIdNames.size());
        for (Map.Entry<DatabaseId, LogChangeReader.DbInfo> entry : 
             reader.getSyncDbs().entrySet()) {
            DatabaseId id = entry.getKey();
            LogChangeReader.DbInfo info = entry.getValue();
            assertTrue(dbIdNames.get(id) != null);
            assertEquals(dbIdNames.get(id), info.name);
        }

        /* Check transactions read from the log are the same as we expect. */
        Iterator<ChangeTxn> changeTxns = reader.getChangeTxns();
        int counter = 0;
        while (changeTxns.hasNext()) {
            doCheck(changeTxns, expectedTxns);

            Transaction writeTxn = env.beginTransaction(null, null);
            reader.discardChanges(writeTxn);
            writeTxn.commit();

            counter++;

            /* 
             * For the first 5 transactions and last transaction, there is only 
             * one changed transaction on the log, commit that transaction 
             * should assign the nextSyncStart to lastSyncEnd. 
             *
             * From the 6th and 7th transaction, the on log changed 
             * transactions includes 3 transactions, so the nextSyncStart 
             * shouldn't change and should be smaller than the lastSyncEnd.
             */
            if (counter <= 5 || counter >= 8) {
                assertEquals(reader.getChangeSet().getLastSyncEnd(),
                             reader.getChangeSet().getNextSyncStart());
            } else {
                assertTrue(reader.getChangeSet().getLastSyncEnd() >
                           reader.getChangeSet().getNextSyncStart());
            }
        }

        /* 
         * The test writes two abort transaction and a read transaction, 
         * LogChangeReader should ignore these two transactons. 
         */
        assertEquals(counter, txnCounter - 3);
    }

    private SyncProcessor createSyncProcessor(List<String> dbNames) {
        
        /*
         * Create the LogChangeReader before doing database changes, so that
         * all following changes can be read.
         */
        SyncProcessor processor =
            new MobileSyncProcessor(env, "test", new MobileConnectionConfig());

        ArrayList<SyncDatabase> syncDbList = new ArrayList<SyncDatabase>();
        for (String dbName : dbNames) {
            SyncDatabase syncDb = 
                new SyncDatabase("ex-" + dbName, dbName, null);
            syncDbList.add(syncDb);
        }
        processor.addDataSet("dataset", syncDbList);

        return processor;
    }

    protected void doCheck(Iterator<ChangeTxn> changeTxns, 
                           Map<Long, MyChangeTxn> expectedTxns) {
        ChangeTxn changeTxn = changeTxns.next();

        /* Get the expected transactions details. */
        MyChangeTxn expectedTxn = 
            expectedTxns.get(changeTxn.getTransactionId());

        /*
         * Make sure there is a corresponding transaction with expected Id
         * exists.
         */
        assertTrue(expectedTxn != null);

        /* Check whether the database names are correct. */
        assertEquals(changeTxn.getDatabaseNames().size(),
                     expectedTxn.getDbNames().size());
        for (String dbName : changeTxn.getDatabaseNames()) {
            assertTrue(expectedTxn.getDbNames().contains(dbName));
        }

        /* Check whether the changes are correct. */
        Iterator<Change> changes = changeTxn.getOperations();
        int changeCounter = 0;
        while (changes.hasNext()) {
            Change change = changes.next();
            MyChange expectedChange = 
                expectedTxn.getChanges().get(changeCounter);

            assertEquals(change.getDatabaseName(), 
                         expectedChange.getDatabaseName());
            assertEquals(change.getType(), expectedChange.getOpType());
            assertEquals(IntegerBinding.entryToInt(change.getKey()),
                         expectedChange.getKey());
            if (change.getData() == null) {
                assertTrue(expectedChange.getData() == null);
            } else {
                assertEquals(StringBinding.entryToString(change.getData()),
                             expectedChange.getData());
            }

            change = null;
            changeCounter++;
        }
        assertEquals(changeCounter, expectedTxn.getChanges().size());

        changeTxn.getDatabaseNames().clear();
        changeTxn = null;
    }

    protected void createTransactionalLog(boolean commit, 
                                          Database db, 
                                          OpType opType,
                                          int startIndex,
                                          int endIndex,
                                          String data,
                                          Map<Long, MyChangeTxn> expectedTxns) 
        throws Exception {

        Transaction txn = beginTransaction();
        MyChangeTxn expectedTxn = new MyChangeTxn(txn.getId());
        doDbOperation
            (startIndex, endIndex, data, db, txn, opType, expectedTxn);
        expectedTxns.put(txn.getId(), expectedTxn);
        if (commit) {
            txn.commit();
        } else {
            txn.abort();
        }
    }

    protected Database createDb(String dbName, boolean duplicate)
        throws Exception {

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setTransactional(true);
        dbConfig.setSortedDuplicates(duplicate);

        return env.openDatabase(null, dbName, dbConfig);
    }

    private Transaction beginTransaction() 
        throws Exception {

        Transaction txn = env.beginTransaction(null, null);
        txnCounter++;

        return txn;
    }

    private void doDbOperation(int beginIndex, 
                               int endIndex, 
                               final String name,
                               Database db,
                               Transaction txn,
                               OpType type,
                               MyChangeTxn expectedTxn)
        throws Exception {

        String dbName = db.getDatabaseName();

        expectedTxn.addDbName(dbName);

        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        for (int i = beginIndex; i <= endIndex; i++) {
            IntegerBinding.intToEntry(i, key);
            if (name != null) {
                StringBinding.stringToEntry(name, data);
            }

            ChangeType changeType = null;
            switch (type) {
                case INSERT:
                    db.put(txn, key, data);
                    changeType = ChangeType.INSERT;
                    break;
                case UPDATE: 
                    db.put(txn, key, data);
                    changeType = ChangeType.UPDATE;
                    break;
                case DELETE:
                    db.delete(txn, key);
                    changeType = ChangeType.DELETE;
                    data = null;
                    break;
                case READ:
                    db.get(txn, key, data, null);
                    break;
                default:
                    throw new IllegalArgumentException
                        ("Unsupported operation type.");
            }

            MyChange myChange = new MyChange(i, name, dbName, changeType);
            expectedTxn.addNewChange(myChange);
        }
    }

    public enum OpType { INSERT, UPDATE, DELETE, READ }

    class MyChange {
        private final int key;
        private final String data;
        private final String dbName;
        private final ChangeType opType;

        public MyChange(int key, 
                        String data, 
                        String dbName, 
                        ChangeType opType) {
            this.key = key;
            this.data = data;
            this.dbName = dbName;
            this.opType = opType;
        }

        public int getKey() {
            return key;
        }

        public String getData() {
            return data;
        }

        public String getDatabaseName() {
            return dbName;
        }

        public ChangeType getOpType() {
            return opType;
        }
    }

    class MyChangeTxn {
        private final long transactionId;
        private final Set<String> dbNames = new HashSet<String>();
        private final ArrayList<MyChange> changes = new ArrayList<MyChange>();

        public MyChangeTxn(long transactionId) {
            this.transactionId = transactionId;
        }

        public void addDbName(String dbName) {
            dbNames.add(dbName);
        }

        public void addNewChange(MyChange newChange) {
            changes.add(newChange);
        }

        public Set<String> getDbNames() {
            return dbNames;
        }

        public List<MyChange> getChanges() {
            return changes;
        }
    }
}
