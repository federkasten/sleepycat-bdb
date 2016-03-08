/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.sync.ChangeReader.Change;
import com.sleepycat.je.sync.ChangeReader.ChangeTxn;
import com.sleepycat.je.sync.ChangeReader.ChangeType;
import com.sleepycat.je.sync.SyncDatabase;
import com.sleepycat.je.sync.SyncProcessor;
import com.sleepycat.je.sync.impl.LogChangeReader;
import com.sleepycat.je.sync.mobile.MobileSyncProcessor;
import com.sleepycat.je.sync.mobile.MobileConnectionConfig;

/*
 * Stress test for testing the SyncDataSet life cycle. 
 *
 * The whole test is divided into two phrases, the first phrase is testing 
 * concurrency while each thread has a different SyncProcessor, the second
 * phrase is testing the concurrency while all threads are using the same
 * SyncProcessor.
 *
 * In each phrase, the test will start three threads, one of the them is a 
 * thread that does database operations to increase the log changes, the rest
 * two threads are the threads that do add/remove/update SyncDataSet 
 * operations, and the test will check the log contents when it does 
 * discardChanges.
 */
public class SyncDataSetStress {
    private static final String oldValue = "abcdefghijklmnopqrstuvwxyz";
    private static final String newValue = oldValue + "0123456789";
    private final ArrayList<String> totalDbNames = new ArrayList<String>();

    /* 
     * Used for concurrent control between adding a SyncDataSet and do database 
     * operations, used in both phrases.
     */ 
    private final HashMap<String, DbObject> nameToDbs = 
        new HashMap<String, DbObject>();

    /*
     * Used for concurrent control between removing a SyncDataSet and updating
     * a SyncDataSet, when the two threads use the same SyncProcessor.
     */
    private final HashMap<String, ReentrantLock> nameToLocks = 
        new HashMap<String, ReentrantLock>();

    /* Environment home. */
    private String homeDir;
    /* Number of running cleaner threads. */
    private int nCleanerThreads = 4;
    /* See EnvironmentConfig.CLEANER_BYTES_INTERVAL. */
    private int nCleanerBytesInterval = 60000;
    /* See EnvironmentConfig.CHECKPOINTER_BYTES_INTERVAL. */
    private int nCkptBytesInterval = 120000;
    /* See EnvironmentConfig.LOG_FILE_MAX. */
    private int logFileMax = 10000;
    /* The number of SyncDataSets created in this SyncProcessor. */
    private int dataSetNumber = 6;
    /* Total operation number. */
    private int totalNumber = 100000;
    private int subDir = 0;

    private volatile int opNumber;

    private Environment env;

    public static void main(String[] args) 
        throws Exception {

        SyncDataSetStress test = new SyncDataSetStress();
        test.parseArgs(args);
        test.doTest();
    }

    public void parseArgs(String[] args) {
        try {
            if (args.length == 0) {
                throw new IllegalArgumentException
                    ("Should specify the Environment home.");
            }

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                boolean moreArgs = (i < args.length - 1);
                if (arg.equals("-h") && moreArgs) {
                    homeDir = args[++i];
                } else if (arg.equals("-threads") && moreArgs) {
                    nCleanerThreads = Integer.parseInt(args[++i]);
                } else if (arg.equals("-fileSize") && moreArgs) {
                    logFileMax = Integer.parseInt(args[++i]);
                } else if (arg.equals("-dataSetNumber") && moreArgs) {
                    dataSetNumber = Integer.parseInt(args[++i]);
                } else if (arg.equals("-cleanerBytes") && moreArgs) {
                    nCleanerBytesInterval = Integer.parseInt(args[++i]);
                } else if (arg.equals("-checkpointerBytes") && moreArgs) {
                    nCkptBytesInterval = Integer.parseInt(args[++i]);
                } else if (arg.equals("-totalNumber") && moreArgs) {
                    totalNumber = Integer.parseInt(args[++i]);
                } else if (arg.equals("-subDir") && moreArgs) {
                    subDir = Integer.parseInt(args[++i]);
                } else {
                    usage("Unknown arg: " + arg);
                }
            }
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            usage("IllegalArguments");
        }
    }

    private void usage(String error) {
        if (error != null) {
            System.err.println(error);
        }

        System.err.println
            ("java " + getClass().getName() + "\n" +
             "     -h                 <env home dir, string>\n" +
             "     -threads           <cleaner threads, numeric>\n" +
             "     -fileSize          <JE log file size, numeric>\n" + 
             "     -dataSetNumber     <number of SyncDataSets, numeric>\n" +
             "     -cleanerBytes      <log cleaning bytes, numeric>\n" +
             "     -checkpointerBytes <checkpointing bytes, numeric>\n" +
             "     -totalNumber       <operation number, numeric>\n");
        System.exit(1);
    }

    public void doTest()  
        throws Exception {

        initDbNames();

        /* Start testing each thread has a different SyncProcessor. */
        startThreads(true);
        closeEnvAndDbs();

        /* 
         * Delete the environment so that the cleaner behaviors would only be 
         * controlled by the SyncProcessor created in the next phrase.
         */
        File envHome = new File(homeDir);
        for (File file : envHome.listFiles()) {
            if (file.isDirectory() && file.getName().startsWith("data")) {
                for (File f : file.listFiles()) {
                    f.delete();
                }
            }
            file.delete();
        }

        /* Start testing all threads have the same SyncProcessor. */ 
        startThreads(false);
        closeEnvAndDbs();
    }

    private void initDbNames() {
        for (int i = 1; i <= dataSetNumber; i++) {
            totalDbNames.add("db" + i);
        }

        for (String dbName : totalDbNames) {
            nameToLocks.put(dbName, new ReentrantLock());
        }
    }

    /* Start the threads that should be running in the test. */
    private void startThreads(boolean multiProcessor) 
        throws Exception {

        createEnv();
        ArrayList<DbObject> dbs = createDbs();

        opNumber = totalNumber / 2;
        CountDownLatch endSignal = new CountDownLatch(3);

        Thread threadB = null;
        Thread threadC = null;
        if (multiProcessor) {
            threadB = createMultiThread("processor1", true, endSignal);
            threadC = createMultiThread("processor2", false, endSignal);
        } else {
            SyncProcessor processor = new MyMobileSyncProcessor
                (env, "processor3", new MobileConnectionConfig());
            ArrayList<String> addAndRemoveDbNames = new ArrayList<String>();
            for (String dbName : totalDbNames) {
                addDataSet(processor, dbName);
                addAndRemoveDbNames.add(dbName);
            }
            threadB = new SingleProcessorThread
                (processor, addAndRemoveDbNames, endSignal);
            threadC = new SingleProcessorThread
                (processor, addAndRemoveDbNames, endSignal);
        }

        /* Start SyncProcessor threads. */
        WorkerThread threadA = new WorkerThread(dbs, endSignal);
        threadA.start();

        Thread.sleep(1000);

        threadB.start();
        threadC.start();

        /* Finish the phrase. */
        endSignal.await();  
    }

    private MultiProcessorThread createMultiThread(String processorName,
                                                   boolean firstPart,
                                                   CountDownLatch endSignal) {
        SyncProcessor processor = new MyMobileSyncProcessor
            (env, processorName, new MobileConnectionConfig());

        ArrayList<String> dbNames = new ArrayList<String>();
        int begin = firstPart ? 0 : totalDbNames.size() / 2;
        int end = firstPart ? totalDbNames.size() /2 : totalDbNames.size();

        for (int i = begin; i < end; i++) {
            dbNames.add(totalDbNames.get(i));
        }

        return new MultiProcessorThread(processor, dbNames, endSignal);
    }

    private void addDataSet(SyncProcessor processor, String name) {
        ArrayList<SyncDatabase> syncDbList = new ArrayList<SyncDatabase>();
        syncDbList.add(new SyncDatabase("ex-" + name, name, null));
        processor.addDataSet(name, syncDbList);
    }

    private void createEnv() 
        throws Exception {

        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);

        /*
         * Configure Environment so that log cleaning can be invoked more often
         * during the test to test cleaner/SyncDataSet concurrency.
         */
        DbInternal.disableParameterValidation(envConfig);
        envConfig.setConfigParam(EnvironmentConfig.LOG_FILE_MAX,
                                 new Integer(logFileMax).toString());
        envConfig.setConfigParam
            (EnvironmentConfig.CLEANER_BYTES_INTERVAL,
             new Integer(nCleanerBytesInterval).toString());
        envConfig.setConfigParam(EnvironmentConfig.CLEANER_THREADS,
                                 new Integer(nCleanerThreads).toString());
        envConfig.setConfigParam(EnvironmentConfig.CHECKPOINTER_BYTES_INTERVAL,
                                 new Integer(nCkptBytesInterval).toString());

        if (subDir > 0) {
            envConfig.setConfigParam
                (EnvironmentConfig.LOG_N_DATA_DIRECTORIES, subDir + "");
            Utils.createSubDirs(new File(homeDir), subDir);
        }

        env = new Environment(new File(homeDir), envConfig);
    }

    private ArrayList<DbObject> createDbs() 
        throws Exception {

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setTransactional(true);

        ArrayList<DbObject> dbs = new ArrayList<DbObject>();
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        for (String dbName : totalDbNames) {
            Database db = env.openDatabase(null, dbName, dbConfig);

            Transaction txn = env.beginTransaction(null, null);
            for (int i = 1; i <= 100; i++) {
                IntegerBinding.intToEntry(i, key);
                StringBinding.stringToEntry(oldValue, data);
                db.put(txn, key, data);
            }
            txn.commit();
            DbObject object = new DbObject(db, dbName, 1, 100);
            dbs.add(object);
            nameToDbs.put(dbName, object);
        }

        return dbs;
    }

    private void closeEnvAndDbs() {
        for (DbObject db : nameToDbs.values()) {
            db.getDb().close();
        }

        for (String dbName : totalDbNames) {
            env.removeDatabase(null, dbName);
        }

        env.close();
    }

    /* Thread used to create database contents. */
    private class WorkerThread extends Thread {
        private final ArrayList<DbObject> dbs;
        private final CountDownLatch endSignal;

        public WorkerThread(ArrayList<DbObject> dbs, 
                            CountDownLatch endSignal) {
            this.dbs = dbs;
            this.endSignal = endSignal;
        }

        public void run() {
            try {
                Random random = new Random();
                while (true) {
                    DbObject object = dbs.get(random.nextInt(dataSetNumber));

                    object.doDeletion();
                    object.doUpdates();
                    object.doInsertion();

                    if (opNumber <= 0) {
                        break;
                    }

                    sleep(100);
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(1);
            } finally {
                endSignal.countDown();
            }
        }
    }

    /* Thread for doing SyncDataSet operations. */
    private class MultiProcessorThread extends Thread {
        private final SyncProcessor processor;
        private final ArrayList<String> dbNames;
        private final CountDownLatch endSignal;

        public MultiProcessorThread(SyncProcessor processor, 
                                    ArrayList<String> externalDbNames,
                                    CountDownLatch endSignal) {
            this.processor = processor;

            dbNames = new ArrayList<String>();
            for (String dbName : externalDbNames) {
                dbNames.add(dbName);
            }

            this.endSignal = endSignal;
        }

        public void run() {
            try {
                Random random = new Random();

                /* Save the current alive SyncDataSets. */
                ArrayList<String> aliveDataSets = new ArrayList<String>();
                for (String dbName : dbNames) {
                    aliveDataSets.add(dbName);
                    addDataSet(processor, dbName);
                }

                while (true) {
                    OpType opType = OpType.nextRandom();
                    String dbName = null;
                    switch (opType) {
                        case ADD:

                            /* 
                             * Do an ADD SyncDataSet operation, if it tries to
                             * add an existed SyncDataSet, catch the exception
                             * and continue to find next random operation.
                             */
                            dbName = 
                                dbNames.get(random.nextInt(dbNames.size()));
                            DbObject object = nameToDbs.get(dbName);

                            if (!object.tryLock()) {
                                continue;
                            }

                            try {
                                /* Add a new SyncDataSet. */
                                addDataSet(processor, dbName);
                            } catch (IllegalStateException e) {
                                continue;
                            } finally {
                                object.unLockObject();
                            }
                            aliveDataSets.add(dbName);
                            break;
                        case REMOVE:

                            /*
                             * Do a REMOVE SyncDataSet operation, if it tries
                             * to remove a deleted SyncDataSet, catch the 
                             * exception and continue to find the next random
                             * operation.
                             */
                            dbName = 
                                dbNames.get(random.nextInt(dbNames.size()));
                            try {
                                /* Remove a SyncDataSet. */
                                processor.removeDataSet(dbName);
                            } catch (IllegalStateException e) {
                                continue;
                            }
                            aliveDataSets.remove(dbName);
                            break;
                        case UPDATE:
                            if (aliveDataSets.size() == 0) {
                                continue;
                            }

                            /* Do the updates and check database contents. */
                            dbName = aliveDataSets.get
                                (random.nextInt(aliveDataSets.size()));
                            readChanges(processor, dbName);
                            break;
                        default:
                            throw new UnsupportedOperationException
                                ("Unrecognized operation.");
                    }

                    if (opNumber % 10000 == 0) {
                        System.err.println("current opNumber: " + opNumber);
                    }

                    if (--opNumber <= 0) {
                        break;
                    }
                }
            } catch (Exception e) {
                /* Finish all the threads if any exceptions happens. */
                e.printStackTrace(); 
                System.exit(1);
            } finally {
                endSignal.countDown();
            }
        }
    }

    /* Read log changes and do the contents check .*/
    private void readChanges(SyncProcessor processor, String dbName) {
        LogChangeReader reader = 
            new LogChangeReader(env, dbName, processor, false, 0);
        Iterator<ChangeTxn> txns = reader.getChangeTxns();
        try {
            while (txns.hasNext()) {
                checkDbContents(txns.next());
                reader.discardChanges(null);
            } 
        } catch (Exception e) {
            e.printStackTrace();
            opNumber = 0;
        }
    }

    private class SingleProcessorThread extends Thread {
        private final SyncProcessor processor;
        private final CountDownLatch endSignal;
        private final ArrayList<String> dbNames;

        public SingleProcessorThread(SyncProcessor processor,
                                     ArrayList<String> dbNames,
                                     CountDownLatch endSignal) {
            this.processor = processor;
            this.endSignal = endSignal;
            this.dbNames = dbNames;
        }

        public void run() {
            try {
                Random random = new Random();

                while (true) {
                    OpType opType = OpType.nextRandom();
                    String dbName = null;
                    switch (opType) {
                        case ADD:
                            dbName = totalDbNames.get
                                (random.nextInt(totalDbNames.size()));
                            DbObject object = nameToDbs.get(dbName);

                            if (!object.tryLock()) {
                                continue;
                            }

                            try {
                                addDataSet(processor, dbName);
                            } catch (IllegalStateException e) {
                                continue;
                            } finally {
                                object.unLockObject();
                            }
                            dbNames.add(dbName);
                            break;
                        case REMOVE:
                            dbName = totalDbNames.get
                                (random.nextInt(totalDbNames.size()));

                            ReentrantLock removeLocker =
                                nameToLocks.get(dbName);
                            if (!removeLocker.tryLock()) {
                                continue;
                            }

                            try {
                                processor.removeDataSet(dbName);
                                dbNames.remove(dbName);
                            } catch (IllegalStateException e) {
                                continue;
                            } finally {
                                removeLocker.unlock();
                            }
                            break;
                        case UPDATE:
                            dbName = totalDbNames.get
                                (random.nextInt(totalDbNames.size()));

                            ReentrantLock updateLocker =
                                nameToLocks.get(dbName);
                            if (!updateLocker.tryLock()) {
                                continue;
                            }

                            try {
                                if (!dbNames.contains(dbName)) {
                                    continue;
                                }

                                readChanges(processor, dbName);
                            } finally {
                                updateLocker.unlock();
                            }
                            break;
                        default:
                            throw new UnsupportedOperationException
                                ("Unrecognized operation.");
                    }

                    if (opNumber % 10000 == 0) {
                        System.err.println("current opNumber: " + opNumber);
                    }

                    if (--opNumber <= 0) {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(1);
            } finally {
                endSignal.countDown();
            }
        }
    }

    /* Check the database contents read by LogChangeReader is correct. */
    private void checkDbContents(ChangeTxn txn) {
        if (txn.getTransactionId() < 0) {
            throw new IllegalStateException("Reading a transaction with " +
                    "negative id in a standalone envrionment.");
        }

        if (txn.getDatabaseNames().size() != 1) {
            throw new IllegalStateException("A SyncDataSet should only " +
                    "reflect to one JE database in the Environment.");
        }

        String dbName = txn.getDatabaseNames().iterator().next();
        if (!totalDbNames.contains(dbName)) {
            throw new IllegalStateException("SyncDataSet is reading " +
                    "contents from a non-existed database.");
        }

        if (dbName != txn.getDataSetName()) {
            throw new IllegalStateException
                ("SyncDataSet is reading contents from a wrong database.");
        }

        Iterator<Change> changes = txn.getOperations();
        int counter = 0;
        int beginKey = 0;
        int latestKey = 0;
        while (changes.hasNext()) {
            Change change = changes.next();
            ChangeType type = change.getType();
            latestKey = IntegerBinding.entryToInt(change.getKey());
            if (counter == 0) {
                beginKey = latestKey;
            }

            String data = (change.getData() == null) ?
                null : StringBinding.entryToString(change.getData());
            switch (type) {
                case DELETE:
                    if (data != null) {
                        throw new IllegalStateException("Shouldn't fill the " +
                                "data field if it's a delete operation.");
                    }
                    break;
                case INSERT:
                    if (!oldValue.equals(data)) {
                        throw new IllegalStateException
                            ("Reading wrong data for a insert operation, " +
                             "data: " + data);
                    }
                    break;
                case UPDATE:
                    if (!newValue.equals(data)) {
                        throw new IllegalStateException
                            ("Reading wrong data for an update operation, " +
                             "data: " + data);
                    }
                    break;
                default:
                    throw new IllegalStateException
                        ("Unrecognized change type.");
            }

            counter++;
        }

        if ((latestKey - beginKey) != 9 && (latestKey - beginKey) != 99) {
            throw new IllegalStateException
                ("Reading data with wrong keys, latestKey: " + latestKey + 
                 ", beginKey: " + beginKey + ", counter: " + counter);
        }

        if (counter != 10 && counter != 100) {
            throw new IllegalStateException("Reading wrong number data.");
        }
    }

    /* Database object used in the test. */
    private class DbObject {
        private final Database db;
        private final String dbName;
        private final DatabaseEntry key = new DatabaseEntry();
        private final DatabaseEntry data = new DatabaseEntry();
        private final Random random = new Random();
        private int beginIndex;
        private int endIndex;
        private ReentrantLock locker = new ReentrantLock();

        public DbObject(Database db, 
                        String dbName, 
                        int beginIndex, 
                        int endIndex) {
            this.db = db;
            this.dbName = dbName;
            this.beginIndex = beginIndex;
            this.endIndex = endIndex;
        }

        public String getDbName() {
            return dbName;
        }

        public Database getDb() {
            return db;
        }

        public synchronized boolean tryLock() {
            return locker.tryLock();
        }

        public void lockObject() {
            locker.lock();
        }

        public synchronized void unLockObject() {
            locker.unlock();
        }

        /* Do database delete operations. */
        public void doDeletion() {
            doDatabaseWork(beginIndex, true, null);
            beginIndex += 10;
        }

        /* Do database insert operations. */
        public void doInsertion() {
            doDatabaseWork(endIndex + 1, false, oldValue);
            endIndex += 10;
        }

        /* Do database update operations. */
        public void doUpdates() {
            doDatabaseWork(beginIndex, false, newValue);
        }

        private void doDatabaseWork(int begin, 
                                    boolean isDelete, 
                                    String value) {
            lockObject();

            try {
                Transaction txn = env.beginTransaction(null, null);
                OperationStatus status = null;
                for (int i = begin; i < begin + 10; i++) {
                    IntegerBinding.intToEntry(i, key);
                    if (isDelete) {
                        status = db.delete(txn, key);
                    } else {
                        StringBinding.stringToEntry(value, data);
                        status = db.put(txn, key, data);
                    }
                    assert status == OperationStatus.SUCCESS;
                }

                /* 30 percent of the update transactions will be aborted. */
                if (random.nextInt(10) <= 3 && newValue.equals(value)) {
                    txn.abort();
                } else {
                    txn.commit();
                }
            } finally {
                unLockObject();
            }
        }
    }

    /* SyncProcessor class which implements the removeDataSet method. */
    private class MyMobileSyncProcessor extends MobileSyncProcessor {
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

    /* SyncDataSet operations type. */
    private enum OpType {
        ADD, REMOVE, UPDATE;

        private final static Random random = new Random();

        /* Randomly generate the next SyncDataSet operation. */
        static OpType nextRandom() {
            int result = random.nextInt(3);

            if (result == ADD.ordinal()) {
                return ADD;
            } else if (result == REMOVE.ordinal()) {
                return REMOVE;
            } else {
                return UPDATE;
            }
        }
    }
}
