/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */
package com.sleepycat.je.rep.txn;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.sleepycat.je.CursorConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.txn.Utils.RollbackData;
import com.sleepycat.je.rep.txn.Utils.SavedData;
import com.sleepycat.je.rep.txn.Utils.TestData;
import com.sleepycat.persist.EntityCursor;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.PrimaryIndex;
import com.sleepycat.persist.StoreConfig;

/**
 * A RollbackWorkload is a pattern of data operations designed to test the
 * permutations of ReplayTxn rollback.
 *
 * Each workload defines a set of operations that will happen before and after
 * the syncup matchpoint. The workload knows what should be rolled back and
 * and what should be preserved, and can check the results. Concrete workload
 * classes add themselves to the static set of WorkloadCombinations, and the
 * RollbackTest generates a test case for each workload element.
 */
abstract class RollbackWorkload {

    private static final String TEST_DB = "testdb";
    private static final String DB_NAME_PREFIX = "persist#" + TEST_DB + "#"; 

    private static final boolean verbose = Boolean.getBoolean("verbose");

    final Set<TestData> saved;
    final Set<TestData> rolledBack;

    private EntityStore store;
    PrimaryIndex<Long, TestData> testIndex;

    final Random rand = new Random(10);

    RollbackWorkload() {
        saved = new HashSet<TestData>();
        rolledBack = new HashSet<TestData>();
    }

    boolean isMasterDiesWorkload() {
        return true;
    }

    void masterSteadyWork(ReplicatedEnvironment master) {
    }

    void beforeMasterCrash(ReplicatedEnvironment master)
        throws DatabaseException {
    }

    void afterMasterCrashBeforeResumption(ReplicatedEnvironment master)
        throws DatabaseException {
    }

    void afterReplicaCrash(ReplicatedEnvironment master)
        throws DatabaseException {
    }

    boolean noLockConflict() {
        return true;
    }

    void releaseDbLocks() {
    }

    void openStore(ReplicatedEnvironment replicator, boolean readOnly)
        throws DatabaseException {

        StoreConfig config = new StoreConfig();
        config.setAllowCreate(true);
        config.setTransactional(true);
        config.setReadOnly(readOnly);
        store = new EntityStore(replicator, TEST_DB, config);
        testIndex = store.getPrimaryIndex(Long.class, TestData.class);
    }

    /**
     * Dump all the data out of the test db on this replicator. Use
     * READ_UNCOMMITTED so we can see the data for in-flight transactions.
     */
    Set<TestData> dumpData(ReplicatedEnvironment replicator)
        throws DatabaseException {

        openStore(replicator, true /* readOnly */);

        Set<TestData> dumpedData = new HashSet<TestData>();
        Transaction txn = replicator.beginTransaction(null, null);
        EntityCursor<TestData> cursor =
            testIndex.entities(txn, CursorConfig.READ_UNCOMMITTED);

        try {
            for (TestData t : cursor) {
                dumpedData.add(t);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            txn.commit();
            close();
        }

        if (verbose) {
            System.out.println("Replicator " + replicator.getNodeName());
            displayDump(dumpedData);
        }

        return dumpedData;
    }

    private void displayDump(Set<TestData> data) {
        for (TestData t : data) {
            System.out.println(t);
        }
    }

    void close()
        throws DatabaseException {
        if (store != null) {
            store.close();
            store = null;
        }
    }

    boolean containsAllData(ReplicatedEnvironment replicator)
        throws DatabaseException {

        Set<TestData> dataInStore = dumpData(replicator);
        if (checkSubsetAndRemove(dataInStore, saved, "saved")) {
            if (!checkSubsetAndRemove(dataInStore, rolledBack,
                                      "rollback")) {
                return false;
            }
        }

        if (dataInStore.size() == 0) {
            return true;
        }
        if (verbose) {
            System.out.println("DataInStore has an unexpected " +
                               "remainder: " + dataInStore);
        }
        return false;
    }

    boolean containsSavedData(ReplicatedEnvironment replicator)
        throws DatabaseException {

        Set<TestData> dataInStore = dumpData(replicator);
        return checkSubsetAndRemove(dataInStore, saved, "saved");
    }

    private boolean checkSubsetAndRemove(Set<TestData> dataInStore,
                                         Set<TestData> subset,
                                         String checkType) {
        if (dataInStore.containsAll(subset)) {
            /*
             * Doesn't work, why?
             * boolean removed = dataInStore.removeAll(subset);
             */
            for (TestData t: subset) {
                boolean removed = dataInStore.remove(t);
                assert removed;
            }
            return true;
        }

        if (verbose) {
            System.out.println("DataInStore didn't contain " +
                               " subset " + checkType +
                               ". DataInStore=" + dataInStore +
                               " subset = " + subset);
        }
        return false;
    }

    /**
     * This workload rolls back an unfinished transaction which is entirely
     * after the matchpoint. It tests a complete undo.
     */
    static class IncompleteTxnAfterMatchpoint extends RollbackWorkload {

        IncompleteTxnAfterMatchpoint() {
            super();
        }

        @Override
        void beforeMasterCrash(ReplicatedEnvironment master)
            throws DatabaseException {

            openStore(master, false /* readOnly */);

            Transaction matchpointTxn = master.beginTransaction(null, null);
            testIndex.put(matchpointTxn,
                          new SavedData(rand.nextInt(), saved));
            testIndex.put(matchpointTxn,
                          new SavedData(rand.nextInt(), saved));

            /* This commit will serve as the syncup matchpoint */
            matchpointTxn.commit();

            /* This data is in an uncommitted txn, it will be rolled back. */
            Transaction rollbackTxn = master.beginTransaction(null, null);
            testIndex.put(rollbackTxn,
                          new RollbackData(rand.nextInt(), rolledBack));
            testIndex.put(rollbackTxn,
                          new RollbackData(rand.nextInt(), rolledBack));
            testIndex.put(rollbackTxn,
                          new RollbackData(rand.nextInt(), rolledBack));
            testIndex.put(rollbackTxn,
                          new RollbackData(rand.nextInt(), rolledBack));

            close();
        }

        /**
         * The second workload should have a fewer number of updates from the
         * incomplete, rolled back transaction from workloadBeforeNodeLeaves,
         * so that we can check that the vlsn sequences have been rolled back
         * too.  This work is happening while the crashed node is still down.
         */
        @Override
        void afterMasterCrashBeforeResumption(ReplicatedEnvironment master)
            throws DatabaseException {

            openStore(master, false /* readOnly */);

            Transaction whileAsleepTxn = master.beginTransaction(null, null);
            testIndex.put(whileAsleepTxn,
                          new SavedData(rand.nextInt(), saved));
            whileAsleepTxn.commit();

            Transaction secondTxn = master.beginTransaction(null, null);
            testIndex.put(secondTxn,
                          new SavedData(rand.nextInt(), saved));
            close();
        }

        @Override
        void afterReplicaCrash(ReplicatedEnvironment master)
            throws DatabaseException {

            openStore(master, false /* readOnly */);

            Transaction whileReplicaDeadTxn =
                master.beginTransaction(null, null);
            testIndex.put(whileReplicaDeadTxn,
                          new SavedData(rand.nextInt(), saved));
            whileReplicaDeadTxn.commit();

            Transaction secondTxn = master.beginTransaction(null, null);
            testIndex.put(secondTxn,
                          new SavedData(rand.nextInt(), saved));
            close();
        }
    }

    /**
     * This workload creates an unfinished transaction in which all operations
     * exist before the matchpoint. It should be preserved, and then undone
     * by an abort issued by the new master.
     */
    static class IncompleteTxnBeforeMatchpoint extends RollbackWorkload {

        IncompleteTxnBeforeMatchpoint() {
            super();
        }

        @Override
        void beforeMasterCrash(ReplicatedEnvironment master)
            throws DatabaseException {

            openStore(master, false /* readOnly */);

            Transaction matchpointTxn = master.beginTransaction(null, null);
            testIndex.put(matchpointTxn,
                          new SavedData(rand.nextInt(), saved));
            testIndex.put(matchpointTxn,
                          new SavedData(rand.nextInt(), saved));

            /* This data is in an uncommitted txn, it will be rolled back. */
            Transaction rollbackTxnA = master.beginTransaction(null, null);
            testIndex.put(rollbackTxnA,
                          new RollbackData(rand.nextInt(), rolledBack));
            testIndex.put(rollbackTxnA,
                          new RollbackData(rand.nextInt(), rolledBack));

            /* This commit will serve as the syncup matchpoint */
            matchpointTxn.commit();

            /* This data is in an uncommitted txn, it will be rolled back. */
            Transaction rollbackTxnB = master.beginTransaction(null, null);
            testIndex.put(rollbackTxnB,
                          new RollbackData(rand.nextInt(), rolledBack));
            close();
        }

        /**
         * The second workload will re-insert some of the data that
         * was rolled back.
         */
        @Override
        void afterMasterCrashBeforeResumption(ReplicatedEnvironment master)
            throws DatabaseException {

            openStore(master, false /* readOnly */);

            Transaction whileAsleepTxn = master.beginTransaction(null, null);
            testIndex.put(whileAsleepTxn,
                          new SavedData(rand.nextInt(), saved));
            whileAsleepTxn.commit();

            Transaction secondTxn =  master.beginTransaction(null, null);
            testIndex.put(secondTxn,
                          new SavedData(rand.nextInt(), saved));
            close();
        }

        @Override
        void afterReplicaCrash(ReplicatedEnvironment master)
            throws DatabaseException {

            openStore(master, false /* readOnly */);

            Transaction whileReplicaDeadTxn =
                master.beginTransaction(null, null);
            testIndex.put(whileReplicaDeadTxn,
                          new SavedData(rand.nextInt(), saved));
            whileReplicaDeadTxn.commit();

            Transaction secondTxn =  master.beginTransaction(null, null);
            testIndex.put(secondTxn,
                          new SavedData(rand.nextInt(), saved));
            close();
        }
    }

    /**
     * This workload creates an unfinished transaction in which operations
     * exist before and after the matchpoint. Only the operations after the
     * matchpoint should be rolled back. Ultimately, the rollback transaction
     * will be aborted, because the master is down.
     */
    static class IncompleteTxnStraddlesMatchpoint extends RollbackWorkload {

        IncompleteTxnStraddlesMatchpoint() {
            super();
        }

        @Override
        void beforeMasterCrash(ReplicatedEnvironment master)
            throws DatabaseException {

            openStore(master, false /* readOnly */);

            Transaction matchpointTxn = master.beginTransaction(null, null);
            testIndex.put(matchpointTxn,
                          new SavedData(rand.nextInt(), saved));
            testIndex.put(matchpointTxn,
                          new SavedData(rand.nextInt(), saved));

            /* This data is in an uncommitted txn, it will be rolled back. */
            Transaction rollbackTxn = master.beginTransaction(null, null);
            testIndex.put(rollbackTxn,
                          new RollbackData(rand.nextInt(), rolledBack));
            testIndex.put(rollbackTxn,
                          new RollbackData(rand.nextInt(), rolledBack));

            /* This commit will serve as the syncup matchpoint */
            matchpointTxn.commit();

            /* This data is in an uncommitted txn, it will be rolled back. */
            testIndex.put(rollbackTxn,
                          new RollbackData(rand.nextInt(), rolledBack));
            close();
        }

        /**
         * The second workload will re-insert some of the data that
         * was rolled back.
         */
        @Override
        void afterMasterCrashBeforeResumption(ReplicatedEnvironment master)
            throws DatabaseException {

            openStore(master, false /* readOnly */);

            Transaction whileAsleepTxn = master.beginTransaction(null, null);
            testIndex.put(whileAsleepTxn,
                          new SavedData(rand.nextInt(), saved));
            whileAsleepTxn.commit();

            Transaction secondTxn =  master.beginTransaction(null, null);
            testIndex.put(secondTxn,
                          new SavedData(rand.nextInt(), saved));
            close();
        }

        @Override
        void afterReplicaCrash(ReplicatedEnvironment master)
            throws DatabaseException {

            openStore(master, false /* readOnly */);

            Transaction whileReplicaDeadTxn = 
                master.beginTransaction(null, null);
            testIndex.put(whileReplicaDeadTxn,
                          new SavedData(rand.nextInt(), saved));
            whileReplicaDeadTxn.commit();

            Transaction secondTxn =  master.beginTransaction(null, null);
            testIndex.put(secondTxn,
                          new SavedData(rand.nextInt(), saved));
            close();
        }
    }

    /**
     * Exercise the rollback of database operations.
     */
    static class DatabaseOpsStraddlesMatchpoint extends RollbackWorkload {

        private DatabaseConfig dbConfig;
        
        private List<String> expectedDbNames;
        private List<String> allDbNames;
        private Transaction incompleteTxn;

        DatabaseOpsStraddlesMatchpoint() {
            super();
            dbConfig = new DatabaseConfig();
            dbConfig.setAllowCreate(true);
            dbConfig.setTransactional(true);
        }

        /* Executed by node that is the first master. */
        @Override
        void beforeMasterCrash(ReplicatedEnvironment master)
            throws DatabaseException {

            Transaction txn1 = master.beginTransaction(null, null);
            Transaction txn2 = master.beginTransaction(null, null);
            Transaction txn3 = master.beginTransaction(null, null);

            Database dbA = master.openDatabase(txn1, "AAA", dbConfig);
            dbA.close();
            Database dbB = master.openDatabase(txn2, "BBB", dbConfig);
            dbB.close();
            
            /* 
             * This will be the syncpoint. 
             * txn 1 is commmited.
             * txn 2 will be partially rolled back.
             * txn 3 will be fully rolled back.
             */
            txn1.commit();

            /* Txn 2 will have to be partially rolled back. */
            master.removeDatabase(txn2, "BBB");

            /* Txn 3 will be fully rolled back. */
            master.removeDatabase(txn3, "AAA");

            expectedDbNames = new ArrayList<String>();
            expectedDbNames.add("AAA");

            allDbNames = new ArrayList<String>();
            allDbNames.add("AAA");
        }

        /* Executed by node that was a replica, and then became master */
        @Override
        void afterMasterCrashBeforeResumption(ReplicatedEnvironment master)
            throws DatabaseException {

            Transaction whileAsleepTxn = master.beginTransaction(null, null);
            Database dbC = master.openDatabase(whileAsleepTxn, "CCC", 
                                               dbConfig);
            dbC.close();
            whileAsleepTxn.commit();

            incompleteTxn = master.beginTransaction(null, null);
            Database dbD = master.openDatabase(incompleteTxn, "DDD", dbConfig);
            dbD.close();

            expectedDbNames = new ArrayList<String>();
            expectedDbNames.add("AAA");
            expectedDbNames.add("CCC");
            expectedDbNames.add("DDD");
        }

        @Override
        void releaseDbLocks() {
            incompleteTxn.commit();
        }

        /* Executed while node that has never been a master is asleep. */
        @Override
        void afterReplicaCrash(ReplicatedEnvironment master)
            throws DatabaseException {

            Transaction whileReplicaDeadTxn =
                master.beginTransaction(null, null);
            master.renameDatabase(whileReplicaDeadTxn,
                                  "CCC", "renamedCCC");
            whileReplicaDeadTxn.commit();
            expectedDbNames = new ArrayList<String>();
            expectedDbNames.add("AAA");
            expectedDbNames.add("renamedCCC");
            expectedDbNames.add("DDD");
        }

        boolean noLockConflict() {
            return false;
        }

        @Override
        boolean containsSavedData(ReplicatedEnvironment master) {
            List<String> names = master.getDatabaseNames();
            if (!(names.containsAll(expectedDbNames) &&
                  expectedDbNames.containsAll(names))) {
                System.out.println("master names = " + names +
                                   " expected= " + expectedDbNames);
                return false;
            }
            return true;
        }

        @Override
        boolean containsAllData(ReplicatedEnvironment master)
            throws DatabaseException {

            List<String> names = master.getDatabaseNames();
            return names.containsAll(allDbNames) &&
                allDbNames.containsAll(names);
        }
    }

    /**
     * An incomplete transaction containing LN writes is rolled back, and then
     * the database containing those LNs is removed.  Recovery of this entire
     * sequence requires rollback to process the LNs belonging to the removed
     * database.  When this test was written, rollback threw an NPE when such
     * LNs were encountered (due to the removed database), and a bug fix was
     * required [#22052].
     */
    static class RemoveDatabaseAfterRollback
        extends IncompleteTxnAfterMatchpoint {

        private boolean removedDatabases = false;

        /*
        @Override
        void afterMasterCrashBeforeResumption(ReplicatedEnvironment master)
            throws DatabaseException {

            super.afterMasterCrashBeforeResumption(master);

            for (String dbName : master.getDatabaseNames()) {
                if (dbName.startsWith(DB_NAME_PREFIX)) {
                    master.removeDatabase(null, dbName);
                }
            }
            removedDatabases = true;
        }
        */

        @Override
        void afterReplicaCrash(ReplicatedEnvironment master)
            throws DatabaseException {

            super.afterReplicaCrash(master);

            for (String dbName : master.getDatabaseNames()) {
                if (dbName.startsWith(DB_NAME_PREFIX)) {
                    master.removeDatabase(null, dbName);
                }
            }
            removedDatabases = true;
        }

        @Override
        boolean containsSavedData(ReplicatedEnvironment replicator)
            throws DatabaseException {

            if (!removedDatabases) {
                return super.containsSavedData(replicator);
            }

            for (String dbName : replicator.getDatabaseNames()) {
                if (dbName.startsWith(DB_NAME_PREFIX)) {
                    return false;
                }
            }

            return true;
        }
    }

    /**
     * This workload simulates a master that is just doing a steady stream of
     * work.
     */
    static class SteadyWork extends RollbackWorkload {

        private Transaction straddleTxn = null;

        @Override
        boolean isMasterDiesWorkload() {
            return false;
        }

        @Override
        void masterSteadyWork(ReplicatedEnvironment master)
            throws DatabaseException {

            if (straddleTxn != null) {
                straddleTxn.commit();
            }

            openStore(master, false /* readOnly */);

            Transaction matchpointTxn = master.beginTransaction(null, null);
            testIndex.put(matchpointTxn,
                          new SavedData(rand.nextInt(), saved));
                         
            testIndex.put(matchpointTxn,
                          new SavedData(rand.nextInt(), saved));

            /* This transaction straddles the matchpoint. */
            straddleTxn = master.beginTransaction(null, null);
            insert();
            insert();

            /* This commit will serve as the syncup matchpoint */
            matchpointTxn.commit();

            for (int i = 0; i < 10; i++) {
                insert();
            }
            
            close();
        }

        private void insert()  {
            TestData d = new SavedData(12, saved);
            testIndex.put(straddleTxn, d);
        }
    }
}
