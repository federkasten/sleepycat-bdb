package com.sleepycat.je.rep.sync;

import java.io.File;
import java.util.ArrayList;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.rep.RepInternal;
import com.sleepycat.je.rep.impl.RepParams;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.sync.SyncDatabase;
import com.sleepycat.je.sync.SyncProcessor;
import com.sleepycat.je.sync.impl.SyncDataSetTest.MyMobileSyncProcessor;
import com.sleepycat.je.sync.mobile.MobileConnectionConfig;

/*
 * Utility class for all replicated data sync tests.
 */
public class RepSyncTestUtils {

    /*
     * Configure the EnvironmentConfig and ReplicationConfig used to create
     * ReplicatedEnvironments, the log cleaning is enabled by default.
     */
    public static RepEnvInfo[] setupEnvInfos(int nodes, File envRoot)
        throws Exception {

        return setupEnvInfos(nodes, envRoot, false, false);
    }

    /*
     * Configure the EnvironemntConfig and ReplicationConfig used to create
     * ReplicatedEnvironments, disable log cleaning depends on the
     * disableLogCleaning parameter.
     */
    public static RepEnvInfo[] setupEnvInfos(int nodes,
                                             File envRoot,
                                             boolean disableLogCleaning,
                                             boolean noSyncDurability)
        throws Exception {

        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);

        /*
         * Set a small log file size so that LocalCBVLSN can advance quickly.
         */
        DbInternal.disableParameterValidation(envConfig);
        envConfig.setConfigParam(EnvironmentConfig.LOG_FILE_MAX, "1000");
        if (disableLogCleaning) {
            envConfig.setConfigParam
                (EnvironmentConfig.ENV_RUN_CLEANER, "false");
            envConfig.setConfigParam
                (EnvironmentConfig.ENV_RUN_CHECKPOINTER, "false");
        }

        if (noSyncDurability) {
            envConfig.setDurability(RepTestUtils.SYNC_SYNC_NONE_DURABILITY);
            envConfig.setConfigParam
                (EnvironmentConfig.ENV_RUN_CHECKPOINTER, "false");
        }

        /* Elect the master. */
        RepEnvInfo[] repEnvInfo =
            RepTestUtils.setupEnvInfos(envRoot, nodes, envConfig);

        for (RepEnvInfo info : repEnvInfo) {
            info.getRepConfig().setConfigParam
                (RepParams.MIN_RETAINED_VLSNS.getName(), "0");
        }

        return repEnvInfo;
    }

    /*
     * Log lots of entries so that we can make sure log entries of SyncDataSet
     * can be read via LogChangeReader.
     */
    public static Database makeFakeEntries(Environment env, String value)
        throws Exception {

        return makeFakeEntries(env, value, true);
    }

    /*
     * Log lots of entries so that we can make sure log entries of SyncDataSet
     * can be read via LogChangeReader, disable the GlobalCBVLSN check depends
     * on parameter checkGlobalCBVLSN.
     */
    public static Database makeFakeEntries(Environment env,
                                           String value,
                                           boolean checkGlobalCBVLSN)
        throws Exception {

        EnvironmentImpl envImpl = DbInternal.getEnvironmentImpl(env);
        long endOfLog = envImpl.getEndOfLog();

        Database fakeDb = createDb(env, "fakeDb");

        for (int i = 1; i <= 50; i++) {
            doDatabaseWork
                (fakeDb, env, (i - 1) * 100 + 1, i * 100, value, false);
        }

        /*
         * Wait until the GlobalCBVLSN larger than endOfLog, so all the log
         * entries for these two SyncDataSets can be read by the
         * LogChangeReader.
         */
        for (int i = 1; i <= 10; i++) {
            if (envImpl.getGroupDurableVLSN().getSequence() < endOfLog) {
                Thread.sleep(2000);
                continue;
            }
            break;
        }

        if (checkGlobalCBVLSN) {
            assert (envImpl.getGroupDurableVLSN().getSequence() >= endOfLog);
        }

        return fakeDb;
    }

    /* Create a database. */
    public static Database createDb(Environment env, String dbName) {
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setTransactional(true);

        return env.openDatabase(null, dbName, dbConfig);
    }

    /* Create SyncProcessor. */
    public static SyncProcessor createProcessor(String processorName,
                                                String[] dbNames,
                                                Environment env)
        throws Exception {

        SyncProcessor processor = new MyMobileSyncProcessor
            (env, processorName, new MobileConnectionConfig());

        for (String dbName : dbNames) {
            ArrayList<SyncDatabase> syncDbList =
                new ArrayList<SyncDatabase>();
            SyncDatabase syncDb =
                new SyncDatabase("ex-" + dbName, dbName, null);
            syncDbList.add(syncDb);
            processor.addDataSet(dbName, syncDbList);
        }

        return processor;
    }

    /* Insert log entries. */
    public static long doDatabaseWork(Database db,
                                      Environment env,
                                      int beginKey,
                                      int endKey,
                                      String value,
                                      boolean isDelete)
        throws Exception {

        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();

        Transaction txn = env.beginTransaction(null, null);
        long txnId = txn.getId();
        for (int i = beginKey; i <= endKey; i++) {
            IntegerBinding.intToEntry(i, key);
            StringBinding.stringToEntry(value, data);
            if (isDelete) {
                db.delete(txn, key);
            } else {
                db.put(txn, key, data);
            }
        }
        txn.commit();

        return txnId;
    }

    /* Do log file flip on all replicas in a replication group. */
    public static void doLogFileFlip(RepEnvInfo[] repEnvInfo) {
        for (RepEnvInfo info : repEnvInfo) {
            RepInternal.getRepImpl(info.getEnv()).forceLogFileFlip();
        }
    }
}
