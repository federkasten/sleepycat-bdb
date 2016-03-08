/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002,2008 Oracle and/or its affiliates.  All rights reserved.
 */
package dplperf.microbench.src;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.text.DecimalFormat;
import java.util.List;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.JEVersion;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.dbi.EnvironmentImpl;

/**
 * Microbench - comparing the performance of JE's BASE and DPL APIs by calling
 * calling APIs to perform the same identical operations. As part of the test,
 * Environment/OS statistics as well as throughput are logged for further
 * regression analysis. A profiling tool is useful for the comparison.
 * 
 * The characteristics of a simple workload are read from command line, threads
 * are forked to perform the workload, and the resulting throughput is reported.
 */
public class Microbench {

    /* Global settings */
    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_FAILURE = 1;

    TestConfig testConfig;

    public Microbench(String args[]) {
        testConfig = new TestConfig(args);
    }

    /**
     * Print the usage.
     */
    public static void usage(String msg) {
        String usageStr;
        if (msg != null) {
            System.err.println(msg);
        }
        usageStr = "Usage: java Microbench\n" +
            "        [-v] [-h <envHome>] [-createDb]\n" +
            "        [-deferredWrite] [-dirtyRead]\n" +
            "        [-preload] [-syncCommit] [-useTxns]\n" +
            "        [-numThreads <numThreads>]\n" +
            "        [-keyRangeSize <keyRangeSize>]\n" +
            "        [-itemsPerTxn <itemsPerTxn>]\n" +
            "        [-cacheSize <cacheSizeInByte>]\n" +
            "        [-duration <durationInSecond>]\n" +
            "        [-numOperations <numOperations>]\n" +
            "        [-numRepeats <numRepeats>]\n" +
            "        [-targetKeyVal <targetKeyVal>]\n" +
            "        [-testType <BASE|DPL|COLLECTION>]\n" +
            "        [-entryType <String|Basic|SimpleTypes|PrimitiveTypes|" +
            "                     ArrayTypes|StringType" +
            "EnumTypes|ProxyTypes|Subclass>]\n" +
            "        [-tupleBinding]\n" +
            "        [-operationType <INSERT|READ|SCAN|DELETE|UPDATE>]\n" +
            "        [-keyType <RANDOM|RANGE|SINGLE|SERIAL>]\n";
        System.err.println(usageStr);
    }

    /* The main method. */
    public static void main(String[] args) {
        Microbench bench = new Microbench(args);
        bench.kickoff();
    }
    
    /* Kick off the microbench test. */
    public void kickoff() {
        try {
            Test test;
            if (testConfig.testType.equals
                    (Microbench.TestConfig.TestType.BASE)) {
                test = new TestJE(testConfig);
            } else {
                test = new TestDPL(testConfig);
            }
            test.run();
        } catch (Exception e) {
            System.err.println("Microbench.kickoff: " + e);
            e.printStackTrace();
            System.exit(EXIT_FAILURE);
        }
    }
    
    /**
     * Parses and contains all test properties.
     */
    static class TestConfig {
        /* Types of test, dbEntry, operation and key. */
        enum TestType {
            BASE, DPL
        }
                
        enum OperationType {
            INSERT, READ, SCAN, DELETE, UPDATE
        }
        
        enum KeyType {
            RANDOM, RANGE, SINGLE, SERIAL
        }
        
        /* Print stats. */
        static boolean verbose = false;
        /* Create database before test starts. */
        static boolean createDb = false;
        /* Set database to deferred write mode. */
        static boolean deferredWrite = false;
        /* Use dirty reads. */
        static boolean dirtyRead = false;
        /* Preload records into memory by doing a scan before the test. */
        static boolean preload = false;
        /* Use synchronous commit. */
        static boolean syncCommit = false;
        /* Use transactions. */
        static boolean useTxns = false;
        /* Use tupleBinding. */
        static boolean tupleBinding = false;
        /* Number of threads to use. */
        static int numThreads = 1;
        /* Number of upper-bound value of the key range. */
        static int keyRangeSize = 0;
        /* Number of items accessed per txn. */
        static int itemsPerTxn = 1;
        /* Cache size, default is 128 * 2M. */
        static long cacheSize = 134217728 * 2;
        /* Environment home. */
        static String envHome = ".";

        /*
         * The test length can either be specified as a duration
         * (number of seconds) or as a number of operations. If the latter
         * is chosen, the set of operations can be repeated multiple times.
         */
        /* Length of test, in seconds. */
        static int duration = 0;
        /* Operations per test phase. */
        static int numOperations = 0;
        /*
         * Number of times the test phase should repeat under the same
         * environment.
         */
        static int numRepeats = 1;

        /* Test type includes the BASE, DPL and COLLECTION apis. */
        static TestType testType = TestType.BASE;
        /* Indicates type of databaseEntry to use, single field or other. */
        static String entryType = "String";
        /* INSERT, READ, SCAN, DELETE, UPDATE */
        static OperationType operationType = OperationType.READ;
        /* RANDOM, RANGE, SINGLE, SERIAL */
        static KeyType keyType = KeyType.SERIAL;
        /* Indicates what key to use for the single key mode. */
        static int targetKeyVal = 0;
        /* Get current JE version. */
        static String version = JEVersion.CURRENT_VERSION.toString();
        /* Save command-line input arguments. */
        static StringBuffer inputArgs = new StringBuffer();

        private TestConfig(String args[]) {

            if (args.length < 2) {
                usage(null);
                System.exit(EXIT_FAILURE);
            }

            try {
                /* Parse command-line input arguments. */
                for (int i = 0; i < args.length; i++) {
                    String arg = args[i];
                    boolean moreArgs = i < args.length - 1;
                    if (arg.equals("-v")) {
                        verbose = true;
                    } else if (arg.equals("-createDb")) {
                        createDb = true;
                    } else if (arg.equals("-deferredWrite")) {
                        deferredWrite = true;
                    } else if (arg.equals("-dirtyRead")) {
                        dirtyRead = true;
                    } else if (arg.equals("-preload")) {
                        preload = true;
                    } else if (arg.equals("-syncCommit")) {
                        syncCommit = true;
                    } else if (arg.equals("-useTxns")) {
                        useTxns = true;
                    } else if (arg.equals("-h") && moreArgs) {
                        envHome = args[++i];
                    } else if (arg.equals("-numThreads") && moreArgs) {
                        numThreads = Integer.parseInt(args[++i]);
                    } else if (arg.equals("-keyRangeSize") && moreArgs) {
                        keyRangeSize = Integer.parseInt(args[++i]);
                    } else if (arg.equals("-itemsPerTxn") && moreArgs) {
                        itemsPerTxn = Integer.parseInt(args[++i]);
                    } else if (arg.equals("-cacheSize") && moreArgs) {
                        cacheSize = Long.parseLong(args[++i]);
                    } else if (arg.equals("-duration") && moreArgs) {
                        duration = Integer.parseInt(args[++i]);
                    } else if (arg.equals("-numOperations") && moreArgs) {
                        numOperations = Integer.parseInt(args[++i]);
                    } else if (arg.equals("-numRepeats") && moreArgs) {
                        numRepeats = Integer.parseInt(args[++i]);
                    } else if (arg.equals("-testType") && moreArgs) {
                        testType = TestType.valueOf(args[++i]);
                    } else if (arg.equals("-entryType") && moreArgs) {
                        entryType = args[++i];
                    } else if (arg.equals("-tupleBinding")) {
                        tupleBinding = true;
                    } else if (arg.equals("-operationType") && moreArgs) {
                        operationType = OperationType.valueOf(args[++i]);
                    } else if (arg.equals("-keyType") && moreArgs) {
                        keyType = KeyType.valueOf(args[++i]);
                    } else if (arg.equals("-targetKeyVal") && moreArgs) {
                        targetKeyVal = Integer.parseInt(args[++i]);
                    } else if (arg.equals("-help")) {
                        usage(null);
                        System.exit(EXIT_SUCCESS);
                    } else {
                        usage("Unknown arg: " + arg);
                        System.exit(EXIT_FAILURE);
                    }
                }
                /* A duration OR number of operations must be specified. */
                if ((duration == 0) && (numOperations == 0)) {
                    throw new IllegalArgumentException
                        ("Either duration or numOperations must be specified");
                }
                
                if ((keyType.equals(KeyType.RANDOM) ||
                            keyType.equals(KeyType.RANGE)) &&
                        (keyRangeSize == 0)) {
                    throw new IllegalArgumentException
                        ("keyRangeSize must be specified.");
                }
                
                /* Save command-line input arguments. */
                for (String s : args) {
                    inputArgs.append(" " + s);
                }
                inputArgs.append(" je.version=" + version);
                System.out.println("\nCommand-line input arguments:\n  " +
                                   inputArgs);
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(EXIT_FAILURE);
            }
        }

        @Override
        public String toString() {
            return inputArgs.toString();
        }
    }

    /**
     * Stats is a convenience class used to accumulate elapsed time and JE
     * statistics
     */
    static class Stats {
        
        /**
         * Prints the application stats, like throughput, operations, etc.
         */
        public void printAppStats(TestThread[] thread) {
            
            /*
             * Count up operations executed and time taken for each thread.
             */
            int totalTxns = 0;
            int totalConflicts = 0;
            int totalOperations = 0;
            int totalDeadlocks = 0;
            double totalSec = 0;
            long firstStart = 0;
            long lastEnd = 0;
            for (int i = 0; i < thread.length; i++) {
                totalTxns += thread[i].getNumTxns();
                totalOperations += thread[i].getNumExecutedOps();
                totalConflicts += thread[i].getNumConflicts();
                totalDeadlocks += thread[i].getNumDeadlocks();

                /*
                 * Find the first start and the last end to calculated elapsed
                 * time.
                 */
                long threadStart = thread[i].getStart();
                if (firstStart == 0) {
                    firstStart = threadStart;
                } else if (threadStart < firstStart) {
                    firstStart = threadStart;
                }
                long threadEnd = thread[i].getEnd();
                if (lastEnd == 0) {
                    lastEnd = threadEnd;
                } else if (threadEnd > lastEnd) {
                    lastEnd = threadEnd;
                }
                double sec = (threadEnd - threadStart)/1e3;
                totalSec += sec;
            }

            double elapsedSec = (lastEnd - firstStart)/1e3;

            /* Report numbers of transactions and operations */

            System.out.println("total transactions:       " + totalTxns);
            System.out.println("total operations:         " + totalOperations);
            System.out.println("total conflicts:          " + totalConflicts);
            System.out.println("total deadlocks:          " + totalDeadlocks);
            System.out.println("total secs (all threads): " + totalSec);
            System.out.println("elapsed secs =            " + elapsedSec);

            /* Report elapsed time and throughput. */
            DecimalFormat f = new DecimalFormat();
            f.setMaximumFractionDigits(2);
            f.setMinimumFractionDigits(2);
            f.setDecimalSeparatorAlwaysShown(true);
            System.out.println("total throughput: " +
                    f.format(totalOperations/totalSec) + " operations/sec");
            System.out.println("total throughput (per thread): " +
                    f.format(totalOperations/(totalSec/thread.length)) +
                    " operations/sec");
            System.out.println("Overall test throughput: " +
                               f.format((totalOperations/elapsedSec)) +
                               " operations/sec");
        }

        /**
         * Prints the OS stats.
         */
        public void printOSStats() {
            OperatingSystemMXBean osb =
                ManagementFactory.getOperatingSystemMXBean();
            System.out.println("OS name:" + osb.getName() +
                               " version:" + osb.getVersion() +
                               " arch:" + osb.getArch() +
                               " processors:" + osb.getAvailableProcessors());
        }

        /**
         * Prints the JE environment stats.
         */
        public void printEnvStats(Environment env, boolean clear)
            throws DatabaseException {
            StatsConfig config = new StatsConfig();
            config.setFast(true);
            config.setClear(clear);
            System.out.println(env.getStats(config));
        }

        /**
         * Prints the JE lock stats.
         */
        public void printLockStats(Environment env, boolean clear)
            throws DatabaseException {
            StatsConfig config = new StatsConfig();
            config.setFast(true);
            config.setClear(clear);
            System.out.println(env.getLockStats(config));
        }

        /**
         * Prints the in memory INs.
         */
        public void printINListSize(Environment env) {
            EnvironmentImpl envImpl = DbInternal.getEnvironmentImpl(env);
            System.out.println("in list size=" +
                    envImpl.getInMemoryINs().getSize());
        }

        /**
         * Prints vm runtime stats.
         */
        public void printRuntimeStats() {
            Runtime r = Runtime.getRuntime();
            long total = r.totalMemory();
            long free = r.freeMemory();
            System.out.println("total=" + total + " free=" + free + " used=" +
                    (total - free));
        }

        /**
         * Prints the GC stats.
         */
        public void ptintGCStats() {

            List<GarbageCollectorMXBean> gcBeans =
                ManagementFactory.getGarbageCollectorMXBeans();

            for (GarbageCollectorMXBean gcBean : gcBeans) {
                String name = gcBean.getName();
                long count = gcBean.getCollectionCount();
                if (count > 0) {
                    System.out.println(name + ".count=" + count);
                }
            }
        }
    }
}
