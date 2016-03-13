/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseNotFoundException;
import com.sleepycat.je.LockConflictException;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.rep.DatabasePreemptedException;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;

public class ReplicaDbOps {
    private RepEnvInfo[] repEnvInfo;
    private ReplicatedEnvironment master;
    private long[] fileDeletions;
    /* List for recording truncated databases' name. */
    private List<String> truncatedList;

    /* Environment home root for replication group. */
    private File envRoot;
    /* Replication group size. */
    private int nNodes = 2;
    /* Database number on each replica. */
    private int dbNumber = 100;
    /* Records number of each database. */
    private long dbSize = 20;
    /* Number of database operations done in steady stage. */
    private int steadyOps = 200000;
    /* Number of database oprations done before doing a sync. */
    private int roundOps = 50000;
    /* Maximum size of database number on replica. */
    private int threshold = 30;
    /* Number of reading threads. */
    private int nThreads = 1;
    private int subDir = 0;
    /* Variables saving the smallest and largest number of the database id. */
    private volatile int beginId;
    private volatile int endId;

    protected void parseArgs(String args[])
        throws Exception {

        for (int i = 0; i < args.length; i++) {
            boolean moreArgs = i < args.length - 1;
            if (args[i].equals("-h") && moreArgs) {
                envRoot = new File(args[++i]);
            } else if (args[i].equals("-repNodeNum") && moreArgs) {
                nNodes = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-dbNumber") && moreArgs) {
                dbNumber = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-dbSize") && moreArgs) {
                dbSize = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-steadyOps") && moreArgs) {
                steadyOps = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-roundOps") && moreArgs) {
                roundOps = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-nThreads") && moreArgs) {
                nThreads = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-subDir") && moreArgs) {
                subDir = Integer.parseInt(args[++i]);
            } else {
                usage("Unknown arg: " + args[i]);
            }
        }

        if (nNodes < 2) {
            throw new IllegalArgumentException
                ("Replication group size should > 2!");
        }

        if (roundOps > steadyOps) {
            throw new IllegalArgumentException
                ("roundOps should be equal or smaller than steadyOps!");
        }
    }

    private void usage(String error) {
        if (error != null) {
            System.err.println(error);
        }
        System.err.println
            ("java " + getClass().getName() + "\n" +
             "     [-h <replication group Environment home dir>]\n" +
             "     [-repNodeNum <replication group size>]\n" +
             "     [-dbNumber <databases' number on each replica>]\n" +
             "     [-dbSize <records' number of each tested database>]\n" +
             "     [-steadyOps <the total database operations' number in " +
             "steady state>]\n" +
             "     [-roundOps <do a sync after doing this number of " +
             "database operations>]\n" +
             "     [-nThreads <number of reading threads on replica>]\n");
        System.exit(2);
    }

    /* 
     * Ramping up stage, init some variables, create databases and 
     * insert records into each database, do a sync in the replication group. 
     */
    public void doRampup()
        throws Exception {

        repEnvInfo =
            Utils.setupGroup(envRoot, nNodes, "5000000", "10000000", subDir);
        master = Utils.getMaster(repEnvInfo);
        fileDeletions = new long[nNodes];
        createAndFillDbs();
        /* Make the list thread-safe. */
        truncatedList = Collections.synchronizedList(new ArrayList<String>());
        Utils.doSyncAndCheck(repEnvInfo);
    }

    /* Open a database with the specified name. */
    private Database openDb(ReplicatedEnvironment repEnv, 
                            String dbName, 
                            Transaction txn,
                            boolean readOnly)
        throws Exception {

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(!readOnly);
        dbConfig.setReadOnly(readOnly);
        dbConfig.setTransactional(true);

        return repEnv.openDatabase(txn, dbName, dbConfig);
    }

    /* Create dbNumber databases and insert records. */
    private void createAndFillDbs() 
        throws Exception {

        for (int i = 1; i <= dbNumber; i++) {
            String dbName = Utils.DB_NAME + new Integer(i).toString();
            insertData(openDb(master, dbName, null, false), null);           
        }
        beginId = 1;
        endId = dbNumber;
    }

    /* Insert records into database. */
    private void insertData(Database db, Transaction txn) 
        throws Exception {

        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        for (int i = 1; i <= dbSize; i++) {
            IntegerBinding.intToEntry(i, key);
            StringBinding.stringToEntry("herococo", data);
            db.put(txn, key, data);
        }
        db.close();
    }

    /* Steady stage, do database operations. */
    public void doSteadyState()
        throws Exception {

        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch endSignal = new CountDownLatch(nThreads);
        for (int i = 0; i < nThreads; i++) {
            new ReplicaDbThread(repEnvInfo[1].getEnv(), 
                                startSignal, 
                                endSignal).start();
        }
        startSignal.countDown();
        updateMasterDbs();
        endSignal.await();
        Utils.closeEnvAndCheckLogCleaning(repEnvInfo, fileDeletions, false);
    }

    /* Do the database operations on the master. */
    private void updateMasterDbs() 
        throws Exception {

        int tempRoundOps = roundOps;
        Random truncatedRandom = new Random();

        while (--steadyOps >= 0) {
            /* Randomly get an operation. */
            OpType type = OpType.nextRandom();

            /* 
             * If the database number is smaller than the threshold or the 
             * database number is larger than dbNumber, then control the 
             * operation type it returns.
             */
            if ((beginId + threshold >= endId && type == OpType.REMOVE) ||
                (endId - beginId >= dbNumber && type == OpType.CREATE)) {
                type = OpType.RENAME;
            }

            String dbName = Utils.DB_NAME;
            Transaction txn = master.beginTransaction(null, null);
            switch (type) {
                case CREATE:
                    /* Create a database and insert records. */
                    dbName += new Integer(endId + 1).toString();
                    endId++;
                    insertData(openDb(master, dbName, txn, false), txn);
                    break;
                case REMOVE:
                    /* Remove a database with sepcified name. */
                    dbName += new Integer(beginId).toString();
                    beginId++;
                    master.removeDatabase(txn, dbName);
                    /* Remove the deleted truncated database in the list. */
                    truncatedList.remove(dbName);
                    break;
                case RENAME:
                    /* Rename a database with specified name. */
                    String oldName = dbName + new Integer(beginId).toString();
                    String newName = dbName + new Integer(endId + 1).toString();
                    beginId++;
                    endId++;

                    /* 
                     * Rename the renamed truncated database and add the new
                     * name of this truncated database into the list. 
                     */
                    truncatedList.remove(oldName);
                    truncatedList.add(newName);
                    master.renameDatabase(txn, oldName, newName);
                    break;
                case TRUNCATE:
                    /* Truncate a random database. */
                    int truncateId = 
                        beginId + truncatedRandom.nextInt(endId - beginId + 1);
                    dbName += new Integer(truncateId).toString();
                    /* Only truncate a database once. */
                    if (!truncatedList.contains(dbName)) {
                        truncatedList.add(dbName);
                        master.truncateDatabase(txn, dbName, false);
                    }
                    break;
            }
            txn.commit();

            /* If finishes a round, then do the sync. */
            if (--tempRoundOps == 0) {
                tempRoundOps = roundOps;
                Utils.doSyncAndCheck(repEnvInfo);
            }
        }

        /* Do a sync when the steady stage is over. */
        if (tempRoundOps != 0) {
            Utils.doSyncAndCheck(repEnvInfo);
        }
    }

    public static void main(String[] args) {
        try {
            ReplicaDbOps test = new ReplicaDbOps();
            test.parseArgs(args);
            test.doRampup();
            test.doSteadyState();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /* Define the database operation type as an enum. */
    enum OpType {
        CREATE, REMOVE, RENAME, TRUNCATE;

        private static Random random = new Random(1);

        static OpType nextRandom() {
            if (random.nextFloat() < .25) {
                return CREATE;
            } else if (random.nextFloat() < .5) {
                return REMOVE;
            } else if (random.nextFloat() < .75) {
                return RENAME;
            } else {
                return TRUNCATE;
            }
        }
    };

    /* Reading thread on replica. */
    class ReplicaDbThread extends Thread {
        private final ReplicatedEnvironment repEnv;
        private final CountDownLatch startSignal;
        private final CountDownLatch endSignal;

        public ReplicaDbThread(ReplicatedEnvironment repEnv,
                               CountDownLatch startSignal,
                               CountDownLatch endSignal) {
            this.repEnv = repEnv;
            this.startSignal = startSignal;
            this.endSignal = endSignal;
        }

        public void run() {
            try {
                startSignal.await();
                
                while (steadyOps != 0) {
                    int start = beginId;
                    int end = endId;
                    /* Running a reading loop from beginId to endId. */
                    for (int i = start; i <= end; i++) {
                        String dbName = 
                            Utils.DB_NAME + new Integer(i).toString();
                        boolean success = false;
                        Transaction txn = repEnv.beginTransaction(null, null);
                        Database db = null;
                        try {

                            /* 
                             * Open the database, if successfully opens, then 
                             * check the data correctness of this database. 
                             */
                            db = openDb(repEnv, dbName, txn, true);
                            success = checkCorrectness(db, txn, i);
                        } catch (DatabaseNotFoundException e) {

                            /* 
                             * If the database is removed, then check whether 
                             * the dbId is larger than the beginId.
                             */
                            if (i >= beginId && i != end) {
                                System.err.println("The database id: " + i + 
                                                   " is not thought to " +
                                                   " between beginId: " + 
                                                   start + " and endId: " + 
                                                   end + ", but it is.");
                                System.exit(-1);
                            }
                            /* Break the loop, reading from the new beginId. */
                            break;
                        } catch (DatabasePreemptedException e) {

                            /* 
                             * Exception is expected if the ReplayTxn remove,
                             * rename or truncated the database while opening
                             * it.  Retry with a new database handle.
                             */
                        } catch (LockConflictException e) {

                            /*
                             * When a deadlock or lock preemption occurs, retry
                             * with a new txn.
                             */
                        } finally {
                            /* Close the database and commit/abort the txn. */
                            if (db != null) {
                                db.close();
                            }
                            if (success) {
                                try {
                                    txn.commit();
                                } catch (DatabasePreemptedException e) {
                                    txn.abort();
                                }
                            } else {
                                txn.abort();
                            }
                        }
                    }
                }
                endSignal.countDown();
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(-1);
            }
        }

        /* Check the data correctness of the specified database. */
        private boolean checkCorrectness(Database db, 
                                         Transaction txn, 
                                         int dbId) 
            throws Exception {

            try {

                /* 
                 * If the database size is 0, check whether it is in the 
                 * truncatedList. If it's not in the list and the database id
                 * is larger or equal to the beginId, then it fails.
                 *
                 * If the database size is not 0, but it is not equal as 
                 * dbSize, it is thought failed too.
                 */
                if (db.count() == 0) {
                    if (!truncatedList.contains(db.getDatabaseName()) &&
                        dbId >= beginId) {
                        System.err.println("Database: " + 
                                           db.getDatabaseName() + " size is " + 
                                           "0, it is thought to be " +
                                           "truncated, but it is not!");
                        System.exit(-1);
                    }
                    return true;
                } else if (db.count() != dbSize) {
                    System.err.println("The database size is not as expected!");
                    System.exit(-1);
                }

                /* Check the data of this database starts from 1 to dbSize. */
                DatabaseEntry key = new DatabaseEntry();
                DatabaseEntry data = new DatabaseEntry();
                for (int i = 1; i <= dbSize; i++) {
                    IntegerBinding.intToEntry(i, key);
                    if (db.get(txn, key, data, null) != 
                        OperationStatus.SUCCESS) {
                        System.err.println
                            ("Checking data correctness failed!");
                        System.exit(-1);
                    } else {
                        if (!"herococo".equals
                                (StringBinding.entryToString(data))) {
                            System.err.println
                                ("Data is not the same as expected!");
                            System.exit(-1);
                        }
                    }
                }
            } catch (DatabasePreemptedException e) {

                /* 
                 * It may throw out DatabasePreemptedException if the master 
                 * does remove/rename/truncate operations during reading. If 
                 * the dbId is larger than beginId, then fails the test.
                 */
                String dbName = 
                    Utils.DB_NAME + new Integer(dbId).toString();
                if (dbId >= beginId && !truncatedList.contains(dbName)) {
                    System.err.println
                        ("The database id should be smaller than beginId");
                    System.exit(-1);
                }

                return false;
            } catch (LockConflictException e) {
                /* Expected, throw to caller. */
                throw e;
            } catch (Exception e) {
                /* Catch other exception, for debug use. */
                e.printStackTrace();
                System.exit(-1);

                return false;
            }

            return true;
        }
    }
}
