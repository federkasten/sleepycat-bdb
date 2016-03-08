package perf;

import java.io.File;
import java.text.DecimalFormat;
import java.util.Random;

import com.sleepycat.bind.tuple.LongBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.Durability;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentStats;
import com.sleepycat.je.JEVersion;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;

/**
 * BDBTest converted to Berkeley DB, Java Edition
 */
public class BDBJETest {

    /* required arguments. */
    private File envHome;
    private int numPuts;
    private long cacheSizeInMB;
    private int dataRecordSize;
    private Durability testDurability;

    /* optional parameters */
    private int printInterval = 50000;
    private boolean verbose = true;
    private int numThreads = 1;
    private int cleanerReadSizeKB = 64;

    /*
     * See JE FAQ at
     * http://www.oracle.com/technology/products/berkeley-db/faq/je_faq.html#35
     * if true, set je.evictor.lruOnly = false, je.evictor.nodesPerScan=100 for
     * random access.
     */
    private boolean lruOnlyCachePolicy = false;

    private final int dataValueSize;
    private final int numPutsPerThread;
    private final int keySize = 8;

    private final TransactionConfig txnConfig;
    private final StatsConfig statsConfig;
    private final DecimalFormat fmt;
    private final long[] perThreadTime;

    /* Shared database and environment handles */
    private Database database;
    private Environment env;


    public static void main(String[] argv) {

        System.err.println("Using Berkeley DB Java Edition version " +
                           JEVersion.CURRENT_VERSION);

        BDBJETest test = new BDBJETest(argv);
        try {
            test.doTest();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    public BDBJETest(String[] argv) {
        parseArgs(argv);
        txnConfig = new TransactionConfig();
        txnConfig.setDurability(testDurability);

        dataValueSize = Math.max(dataRecordSize - keySize, 8);
        numPutsPerThread = numPuts / numThreads;
        perThreadTime = new long[numThreads];

        statsConfig = new StatsConfig();
        statsConfig.setClear(true);
        statsConfig.setFast(true);
        fmt = new DecimalFormat();
        fmt.setMaximumFractionDigits(0);
    }

    private void parseArgs(String argv[]) {

        int argc = 0;
        int nArgs = argv.length;

        if (nArgs < 10) {
            printUsage(null);
            System.exit(0);
        }

        while (argc < nArgs) {
            String thisArg = argv[argc++];
            if (thisArg.equals("-h")) {
                checkNumArgs(argc, nArgs);
                envHome = new File(argv[argc++]);
            } else if (thisArg.equals("-n")) {
                checkNumArgs(argc, nArgs);
                numPuts = Integer.parseInt(argv[argc++]);
            } else if (thisArg.equals("-c")) {
                checkNumArgs(argc, nArgs);
                cacheSizeInMB = Integer.parseInt(argv[argc++]);
            } else if (thisArg.equals("-s")) {
                checkNumArgs(argc, nArgs);
                dataRecordSize = Integer.parseInt(argv[argc++]);
            } else if (thisArg.equals("-x")) {
                checkNumArgs(argc, nArgs);
                String durabilityArg = argv[argc++];
                if (durabilityArg.compareToIgnoreCase("noSync") == 0) {
                    testDurability = Durability.COMMIT_NO_SYNC;
                } else if (durabilityArg.compareToIgnoreCase("writeNoSync")
                           == 0) {
                    testDurability = Durability.COMMIT_WRITE_NO_SYNC;
                } else {
                    printUsage("-t requires noSync or writeNoSync");
                }
            } else if (thisArg.equals("-i")) {
                checkNumArgs(argc, nArgs);
                printInterval = Integer.parseInt(argv[argc++]);
            } else if (thisArg.equals("-v")) {
                checkNumArgs(argc, nArgs);
                verbose = Boolean.parseBoolean(argv[argc++]);
            } else if (thisArg.equals("-t")) {
                checkNumArgs(argc, nArgs);
                numThreads = Integer.parseInt(argv[argc++]);
            } else if (thisArg.equals("-r")) {
                checkNumArgs(argc, nArgs);
                lruOnlyCachePolicy = Boolean.parseBoolean(argv[argc++]);
            } else if (thisArg.equals("-z")) {
                checkNumArgs(argc, nArgs);
                cleanerReadSizeKB = Integer.parseInt(argv[argc++]);
            } else  {
                printUsage(null);
            }
        }

        if ((envHome == null) ||
            (numPuts == 0) ||
            (cacheSizeInMB == 0) ||
            (dataRecordSize == 0) ||
            (testDurability == null)) {
            printUsage(null);
        }
    }

    private void checkNumArgs(int argc, int nArgs) {
        if (argc >= nArgs) {
            printUsage("not enough parameters");
            System.exit(-1);
        }
    }

    void doTest()
        throws Exception {

        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);
        envConfig.setCacheSize(cacheSizeInMB << 20l);
        envConfig.setConfigParam("je.cleaner.readSize",
                                 Integer.toString(cleanerReadSizeKB << 10));
        envConfig.setDurability(testDurability);
        if (!lruOnlyCachePolicy) {
            /*
             * These settings encourage the cache to prefer leaving the
             * internal nodes of the btree in cache, rather than evicting
             * strictly by lru.  Increasing nodesPerScan increases the cost of
             * eviction, but makes the eviction run more accurate.
             */
            envConfig.setConfigParam("je.evictor.lruOnly", "false");
            envConfig.setConfigParam("je.evictor.nodesPerScan", "100");
        }

        /*
         * It may also be useful to increase EnvironmentConfig.CLEANER_THREADS
         * (je.cleaner.threads).
         */
        env = new Environment(envHome, envConfig);

        /*
         * Consult the created environment for the configurations it is
         * using. Note that when looking at the durablity setting, consult the
         * je.txn.durability property, and not the txnNoSync, txnWriteNoSync
         * values, as those two are deprecated.
         */
        System.err.println("environment configuration:\n" + env.getConfig());

        System.err.println("test configuration:" +
                           "\nnumPuts=" +
                           fmt.format((numThreads * numPutsPerThread)) +
                           " numPutsPerThread=" +
                           fmt.format(numPutsPerThread) +
                           "\nkeySize=" + keySize +
                           "\ndataValueSize=" + dataValueSize +
                           "\nnumThreads=" + numThreads +
                           "\nprintInterval=" + printInterval +
                           "\nverbose=" + verbose);


        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setTransactional(true);
        database = env.openDatabase(null, "TEST", dbConfig);

        System.err.println
            ("Thread0_numOps\ttxns/sec\tcacheMiss\tcacheBytes\tcleanerRuns\tevictPasses");
        Thread[] putThreads = new Thread[numThreads];
        for (int i=0; i < numThreads; i++) {
            putThreads[i] = new BDBPutThread(i);
            putThreads[i].start();
        }

        for (int i=0; i < numThreads; i++) {
            putThreads[i].join();
        }

        for (int i=0; i < numThreads; i++) {
            long totalTime = perThreadTime[i];
            System.err.println("thread " + i + ": totalSeconds = " +
                               ((float)totalTime)/1000 + " numTxns=" +
                               fmt.format(numPutsPerThread) +
                               " txns/sec = " +
                               fmt.format(numPutsPerThread/
                                          (((float)totalTime)/1000)));
        }

        database.close();
        env.close();
    }

    private void printUsage(String msg) {
        if (msg != null) {
                System.err.println(msg + "\n");
        }

        System.err.println
            ("java -cp je.jar BDBJETest\n" +
             " -h <environment home directory>\n" +
             " -n <num records>\n" +
             " -c <cacheSize in MB>\n" +
             " -s <data record size, in bytes>\n" +
             " -x <transaction_flags (noSync|writeNoSync)>\n" +
             " [-i <txn interval to print stats>\n]" +
             " [-v <verbose (true|false)>" +
             " [-t <numThreads>" +
             " [-r <lru cache policy, false favors random access <true|false>]"+
             " [-z <cleaner read size in K bytes>]");
        System.exit(-1);
    }


    private class BDBPutThread extends Thread {

        private final int threadId; /* Used to coordinate stat dumps. */

        BDBPutThread(int id) {
            threadId = id;
        }

        @Override
        public void run() {
            /* Setup key/data. The key will be random 8 byte longs. */
            Random rand = new Random();
            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry dataValue = new DatabaseEntry();
            byte[] testData = new byte[dataValueSize];

            /* Set data to arbitrary value. */
            for (int i = 0; i < dataValueSize; i++) {
                testData[i] = 'b';
            }
            dataValue.setData(testData);

            long startTime = System.currentTimeMillis();
            long intervalStartTime = startTime;

            for (int i = 1; i <= numPutsPerThread; i++) {
                /* key is set to a random long. */
                LongBinding.longToEntry(rand.nextLong(), key);

                Transaction txn = env.beginTransaction(null, txnConfig);
                OperationStatus status = database.put(txn, key, dataValue);
                if (status != OperationStatus.SUCCESS) {
                    throw new RuntimeException("status = " + status);
                }
                txn.commit();

                if (verbose) {

                    /*
                     * Printing environment stats with the stats clear option
                     * only really makes sense from a single thread. Stats can
                     * be printed with a System.out.println(stats), but are
                     * listed here for columnar formatting.
                     */
                    if ((threadId == 0) &&
                        (((i % printInterval) == 0 ) && (i > 0))) {

                        long delta = System.currentTimeMillis() -
                                     intervalStartTime;
                        EnvironmentStats stats = env.getStats(statsConfig);

                        System.err.printf
                            ("%s\t\t%s\t\t%s\t\t%s\t%s\t\t%s\n",
                             fmt.format(i),
                             fmt.format(printInterval/((float)delta/1000)),
                             fmt.format(stats.getNCacheMiss()),
                             fmt.format(stats.getCacheTotalBytes()),
                             fmt.format(stats.getNCleanerRuns()),
                             fmt.format(stats.getNEvictPasses()));
                        intervalStartTime = System.currentTimeMillis();
                    }
                }
            }

            /*
             * Save per thread time for throughput calculation. This includes
             * verbose printing time.
             */
            perThreadTime[threadId] = System.currentTimeMillis() - startTime;
        }
    }
}

