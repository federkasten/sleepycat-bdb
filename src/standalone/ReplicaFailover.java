/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

import java.io.File;
import java.util.Random;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.PrimaryIndex;

/**
 * Note: add something that deletes the directories, in the ant file.
 */
public class ReplicaFailover {
    private final boolean verbose = Boolean.getBoolean("verbose");

    /* Master of the replication group. */
    private ReplicatedEnvironment master;
    private RepEnvInfo[] repEnvInfo;

    /* Number of files deleted by Cleaner on each node. */
    private long[] fileDeletions;

    /* Used for generating random keys. */
    private final Random random = new Random();

    /* Configuration used when get EnvironmentStats. */
    private StatsConfig statsConfig;

    /* -------------------Configurable params----------------*/
    /* Environment home root for whole replication group. */
    private File envRoot;
    /* Replication group size. */
    private int nNodes = 5;
    /* Database size. */
    private int dbSize = 2000;

    /* Steady state would finish after doing this number of operations. */
    private int steadyOps = 6000000;

    /* Size of each JE log file. */
    private String logFileSize = "409600";

    /* Checkpointer wakes up when JE writes checkpointBytes bytes. */
    private String checkpointBytes = "1000000";

    /* Select a new master after doing this number of operations. */
    private int roundOps = 30;
    private final int opsPerTxn = 20;
    private int uniqueData = dbSize + 1;
    private int subDir = 0;

    private int shutdownStart;

    public void doRampup()
        throws Exception {

        statsConfig = new StatsConfig();
        statsConfig.setFast(false);
        statsConfig.setClear(true);

        repEnvInfo = Utils.setupGroup
            (envRoot, nNodes, logFileSize, checkpointBytes, subDir);
        master = Utils.getMaster(repEnvInfo);
        fileDeletions = new long[nNodes];
        RepTestData.insertData(Utils.openStore(master, Utils.DB_NAME), dbSize);
        Utils.doSyncAndCheck(repEnvInfo);
    }

    /*
     * TODO: when replication mutable property is ready, need to test the two
     * node replication.
     */
    public void doSteadyState()
        throws Exception {

        /* Used to check whether steadyOps is used up. */
        if (verbose) {
            System.out.println("Steady state starting");
        }

        int round = 0;

        /*
         * In this loop, the master stays up. The replicas are killed in a
         * round robin fashion. No need to sync up between rounds; avoiding the
         * syncup and check of data makes for more variation.
         */
        while (true) {
            round++;
            if (verbose) {
                System.err.println ("Round " + round);
            }

            master = Utils.getMaster(repEnvInfo);

            /*
             * If doWork returns false, it means the steadyOps is used up, so
             * break the loop.
             */
            if (!doWorkAndKillReplicas(round)) {
                break;
            }
        }

        /*
         * Re-open the closed nodes and have them re-join the group. Do a sync
         * and check here to check that results are correct.
         */
        master = Utils.getMaster(repEnvInfo);
        Utils.doSyncAndCheck(repEnvInfo);

        /* Close the environment and check the log cleaning. */
        Utils.closeEnvAndCheckLogCleaning(repEnvInfo, fileDeletions, false);
    }

    /*
     * Shutdown some of the replicas and save how many files are deleted by the
     * Cleaner on those nodes in this round.
     */
    private int shutdownReplicas(int lastShutdownIndex)
        throws DatabaseException {

        int nShutdown = nNodes - RepTestUtils.getQuorumSize(nNodes);
        int shutdownIdx = lastShutdownIndex;

        while (nShutdown > 0) {
            if (!repEnvInfo[shutdownIdx].isMaster()) {
                if (verbose) {
                    System.err.println("Closing node: " + (shutdownIdx + 1));
                }

                /* Save the nCleanerDeletions stat on this replicator. */
                fileDeletions[shutdownIdx] = fileDeletions[shutdownIdx] +
                    repEnvInfo[shutdownIdx].getEnv().
                    getStats(statsConfig).getNCleanerDeletions();

                repEnvInfo[shutdownIdx].abnormalCloseEnv();
                if (verbose) {
                    System.err.println("File deletions on node " +
                                       (shutdownIdx) +
                                       ": " + fileDeletions[shutdownIdx]);
                }
                nShutdown--;
            }

            shutdownIdx++;
            if (shutdownIdx == nNodes) {
                shutdownIdx = 0;
            }
        }
        return shutdownIdx;
    }

    /**
     * The master executes this work. The replicas are killed off in the middle
     * of a round, so that they die on a non-commit boundary, and need to do
     * some rollback.
     *
     * @return false if the rampup stage has finished.
     */
    private boolean doWorkAndKillReplicas(int round)
        throws Exception {

        boolean runAble = true;

        EntityStore dbStore = Utils.openStore(master, Utils.DB_NAME);
        PrimaryIndex<Integer, RepTestData> primaryIndex =
            dbStore.getPrimaryIndex(Integer.class, RepTestData.class);

        boolean mustKillReplicas = true;
        for (int r = 0; r  < roundOps; r++) {
            Transaction txn = master.beginTransaction(null, null);
            for (int i = 0; i < opsPerTxn; i++) {
                /* Do a random update here. */
                int key = random.nextInt(dbSize);
                RepTestData data = new RepTestData();
                data.setKey(key);
                data.setData(uniqueData++);
                data.setName("test" + r  + (new Integer(key)).toString());
                primaryIndex.put(txn, data);

                /*
                 * Kill the replicas once during this method, in the middle
                 * of a transaction.
                 */
                if (mustKillReplicas) {
                    shutdownStart = shutdownReplicas(shutdownStart);
                    mustKillReplicas = false;
                }
            }
            txn.commit();

            /* Check whether the steady stage should break. */
            if (--steadyOps == 0) {
                runAble = false;
                break;
            }
        }
        dbStore.close();

        return runAble;
    }

    public void parseArgs(String args[])
        throws Exception {

        for (int i = 0; i < args.length; i++) {
            boolean moreArgs = i < args.length - 1;
            if (args[i].equals("-h") && moreArgs) {
                envRoot = new File(args[++i]);
            } else if (args[i].equals("-repNodeNum") && moreArgs) {
                nNodes = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-dbSize") && moreArgs) {
                dbSize = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-logFileSize") && moreArgs) {
                logFileSize = args[++i];
            } else if (args[i].equals("-steadyOps") && moreArgs) {
                steadyOps = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-roundOps") && moreArgs) {
                roundOps = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-checkpointBytes") && moreArgs) {
                checkpointBytes = args[++i];
            } else if (args[i].equals("-subDir") && moreArgs) {
                subDir = Integer.parseInt(args[++i]);
            } else {
                usage("Unknown arg: " + args[i]);
            }
        }

        if (nNodes <= 2) {
            throw new IllegalArgumentException
                ("Replication group size should > 2!");
        }

        if (steadyOps < roundOps) {
            throw new IllegalArgumentException
                ("steadyOps should be larger than roundOps!");
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
             "     [-dbSize <records' number of the tested database>]\n" +
             "     [-logFileSize <JE log file size>]\n" +
             "     [-checkpointBytes <Checkpointer wakeup interval bytes>]\n" +
             "     [-steadyOps <total update operations in steady state>]\n" +
             "     [-roundOps <select a new master after running this " +
             "number of operations>]\n" +
             "     [-forceCheckpoint <true if invoke Checkpointer " +
             "explicitly>]\n");
        System.exit(2);
    }

    public static void main(String args[]) {
        try {
            ReplicaFailover test = new ReplicaFailover();
            test.parseArgs(args);
            test.doRampup();
            test.doSteadyState();
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
