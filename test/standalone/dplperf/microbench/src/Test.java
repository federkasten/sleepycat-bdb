/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002,2008 Oracle and/or its affiliates.  All rights reserved.
 */
package dplperf.microbench.src;
import java.io.File;
import java.util.Random;

import com.sleepycat.je.Database;
import com.sleepycat.je.Environment;

/**
 * Test - responsible for performing the workload. It is a interface and
 * contains the final members and common methods of the test.
 */
public abstract class Test {
    /* Global settings */
    protected static final int EXIT_SUCCESS = 0;
    protected static final int EXIT_FAILURE = 1;
    protected static boolean done = false;
    /* Variables */
    protected Environment env;
    protected Database db;
    protected Microbench.TestConfig testConfig;
    protected Microbench.Stats stats;
    
    public Test(Microbench.TestConfig testConfig) {
        this.testConfig = testConfig;
    }
    
    /* The execution method. */
    public void run() {
        try {
            setup();

            /*
             * Fork off the threads to perform the transactions. Generate a set
             * of random number generators to pass to threads, do this at this
             * outer level so that we can have different random number streams
             * for each thread that work for repeated runs but have consistent
             * behavior thread after thread.
             */
            for (int repeats = 0; repeats < testConfig.numRepeats; repeats++) {
                done = false;
                TestThread[] threads = new TestThread[testConfig.numThreads];
                Random[] randoms = new Random[testConfig.numThreads];
                for (int i = 0; i < testConfig.numThreads; i++) {
                    randoms[i] =
                        new Random(i + (repeats * testConfig.numThreads));
                    threads[i] = createThread(repeats, i, randoms[i]);
                }

                for (int i = 0; i < testConfig.numThreads; i++) {
                    threads[i].start();
                }

                /* Sleep for the duration of the test. */
                if (testConfig.numOperations == 0) {
                    try {
                        Thread.sleep(testConfig.duration * 1000);
                    } catch (InterruptedException e) {
                        System.out.println(e);
                    }
                    done = true;
                }

                /* Wait for them to finish. */
                for (int i = 0; i < testConfig.numThreads; i++) {
                    try {
                        threads[i].join();
                    } catch (InterruptedException IE) {
                        System.err.println
                            ("caught unexpected InterruptedException");
                        System.exit(EXIT_FAILURE);
                    }
                }
                /* Dump statistics per repeat. */
                if (testConfig.verbose) {
                    System.out.println("=====> Dump statistics at repeats: " +
                            repeats + " <=====");
                    stats.printAppStats(threads);
                    stats.printOSStats();
                    stats.printEnvStats(env, true);
                    stats.printINListSize(env);
                    stats.printLockStats(env, true);
                    stats.printRuntimeStats();
                    System.out.println("=====> Finish dump statistics <=====");
                }
            }
            /* Close the database and environment. */
            close();
            
            /*if (testConfig.operationType.equals(Microbench.
                    TestConfig.OperationType.INSERT)) {
                emptyDir(new File(testConfig.envHome));
            }*/
        } catch (Exception e) {
            System.err.println("Test: " + e);
            e.printStackTrace();
            System.exit(EXIT_FAILURE);
        }
    }
    
    public static void emptyDir(File dir) {
        if (dir.isDirectory()) {
            String[] files = dir.list();
            if (files != null) {
                for (int i = 0; i < files.length; i += 1) {
                    new File(dir, files[i]).delete();
                }
            }
        } else {
            dir.delete();
            dir.mkdirs();
        }
    }

    public boolean isDone() {
        return done;
    }
    
    public abstract void setup() throws Exception;

    public abstract void preload() throws Exception;
    
    public abstract void close() throws Exception;
    
    public abstract TestThread createThread(int repeat,
                                            int whichThread,
                                            Random rnd);

    public abstract String getVersion();
}