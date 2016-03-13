/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002,2008 Oracle and/or its affiliates.  All rights reserved.
 */
package dplperf.microbench.src;

import java.util.Random;

/**
 * TestThread - an abstract class for the threads used by the TestJE and TestDPL
 * classes.
 */
public abstract class TestThread extends Thread {
    /* Global settings */
    protected static final int EXIT_SUCCESS = 0;
    protected static final int EXIT_FAILURE = 1;

    /* The test that we are a part of. */
    protected Test test;
    /* The configuration of the test. */
    protected Microbench.TestConfig testConfig;
    /* Thread id */
    protected int id;
    /* Start time */
    private long start;
    /* End time */
    private long end;
    /* Num txns completed */
    private int numTxns;
    /* Num operations executed */
    private int numExecutedOps;
    /* Num operation failures */
    protected int numConflicts;
    /* Num deadlocks seen, results in retries */
    private int numDeadlocks;
    /* Random number generator */
    private Random rnd;
    /* which repeat it is */
    private int repeat;

    public TestThread(Test test,
                      int repeat,
                      int id,
                      Random rnd,
                      Microbench.TestConfig testConfig) {
        this.test = test;
        this.id = id;
        this.start = this.end = 0;
        this.rnd = rnd;
        this.repeat = repeat;
        this.testConfig = testConfig;
    }

    public void run() {

        System.out.println("Access method thread: " + id + " started.");

        /* Allow the other threads to start. */
        yield();

        /* Record our start time. */
        start = System.currentTimeMillis();

        numTxns = 0;
        numConflicts = 0;

        boolean useDuration = true;
        int requiredOperations = 0;
        if (testConfig.numOperations != 0) {
            requiredOperations =
                testConfig.numOperations / testConfig.numThreads;
            useDuration = false;
        }

        int orderedKey = 0;
        try {

            /*
             * Do a scan if it's a scan operation or a delete. If it's a delete,
             * we need to do something to bring the database into memory first.
             */
            if (testConfig.operationType.
                    equals(Microbench.TestConfig.OperationType.SCAN)) {
                numExecutedOps = scan();
            } else {
                numExecutedOps = 0;
                boolean done = false;

                /*
                 * If using ordered keys, each thread selects a different set of
                 * keys, in round robin fashion. For example, with 2 threads,
                 * thread 1 uses keys 0, 2, ..., thread2 uses keys 1, 3, ... If
                 * this test inserts data, each test repeat should create new
                 * data and orderedKey should jump to a new value; for other
                 * operations, we'd like the orderedKey to repeat the previous
                 * set of keys.
                 */
                if (testConfig.operationType.
                        equals(Microbench.TestConfig.OperationType.INSERT)) {
                    orderedKey = id + repeat * testConfig.numOperations;
                } else {
                    orderedKey = id;
                }
                while (!done) {
                    Object txn = null;
                    try {
                        /* Begin the transaction. */
                        if (testConfig.useTxns) {
                            txn = beginTransaction();
                            numTxns++;
                        }

                        /* Perform the operations. */
                        boolean execOk = true;
                        for (int j = 0; j < testConfig.itemsPerTxn; j++) {
                            selectKey(orderedKey);
                            if (testConfig.operationType.equals(Microbench.
                                    TestConfig.OperationType.READ)) {
                                databaseGet(txn);
                            }
                            if (testConfig.operationType.equals(Microbench.
                                    TestConfig.OperationType.DELETE)) {
                                databaseDelete(txn);
                            } else {
                                if (testConfig.operationType.equals(Microbench.
                                        TestConfig.OperationType.INSERT)) {
                                    databasePutNoOverwrite(txn);
                                } else if (testConfig.operationType.
                                        equals(Microbench.TestConfig.
                                                OperationType.UPDATE)) {
                                    databaseUpdate(txn);
                                }
                            }

                            if (execOk) {
                                numExecutedOps++;
                                orderedKey += testConfig.numThreads;
                            }

                            /* See if we're done. */
                            if (useDuration) {
                                if (test.isDone()) {
                                    done = true;
                                }
                            } else {
                                if (numExecutedOps >= requiredOperations) {
                                    done = true;
                                }
                            }
                            if (done) {
                                break;
                            }
                        }

                        /* Commit the transaction. */
                        if (testConfig.useTxns && txn != null) {
                            commitTxn(txn);
                        }

                        if (done) {
                            break;
                        }
                    } catch (Exception e) {
                        /* Deal with deadlock and other errors. */
                        if (txn != null) {
                            try {
                                abortTxn(txn);
                            } catch (Exception e2) {
                                System.err.println("abort: " + e2);
                                System.err.println("original exception: " + e);
                                System.exit(EXIT_FAILURE);
                            }
                        }
                        exitIfNotDeadlock(e);
                        /* else will retry on next iteration */
                        numDeadlocks++;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }

        /* Record our end time. */
        end = System.currentTimeMillis();

        System.out.println("Access method thread " + id + " exiting cleanly.");
        System.out.println("Total operation time = " + (end - start) + "ms");
    }

    protected abstract void assignKey(int val);

    protected abstract Object beginTransaction() throws Exception;

    protected abstract void abortTxn(Object txn) throws Exception;

    protected abstract void commitTxn(Object txn) throws Exception;

    protected abstract void databasePutNoOverwrite(Object txn) throws Exception;

    protected abstract void databaseUpdate(Object txn) throws Exception;

    protected abstract void databaseGet(Object txn) throws Exception;

    protected abstract void databaseDelete(Object txn) throws Exception;

    protected abstract void exitIfNotDeadlock(Exception e);

    protected abstract int scan() throws Exception;

    /* Used for collect statistics. */
    public long getStart() {
        return start;
    }

    public long getEnd() {
        return end;
    }

    public int getNumTxns() {
        return numTxns;
    }

    public int getNumExecutedOps() {
        return numExecutedOps;
    }

    public int getNumConflicts() {
        return numConflicts;
    }

    public int getNumDeadlocks() {
        return numDeadlocks;
    }

    /**
     * Select the key. It may be chose in one of four ways:
     *  - random value 
     *  - value within a range 
     *  - value tied to the current iteration
     *  - a single, present value.
     * Help choose a random key, where the first character is is one of
     * numFirstChars characters starting with 'a', and the remaining
     * characters are one of numChars characters starting with 'a'.
     */
    protected void selectKey(int iterationVal) {
        int keyVal = 0;
        if (testConfig.keyType.equals(Microbench.TestConfig.KeyType.SINGLE)) {
            keyVal = testConfig.targetKeyVal;
        } else if (testConfig.keyType.equals
                (Microbench.TestConfig.KeyType.RANDOM)) {
            keyVal = rnd.nextInt(testConfig.keyRangeSize);
        } else if (testConfig.keyType.equals
                (Microbench.TestConfig.KeyType.SERIAL)) {
            keyVal = iterationVal;
        } else if (testConfig.keyType.equals
                (Microbench.TestConfig.KeyType.RANGE)) {
            keyVal = rnd.nextInt(testConfig.keyRangeSize);
        }
        assignKey(keyVal);
    }
}
