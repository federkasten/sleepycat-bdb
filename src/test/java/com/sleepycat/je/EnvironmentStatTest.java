/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.dbi.MemoryBudget;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

public class EnvironmentStatTest extends TestBase {

    private final File envHome;
    private static final String DB_NAME = "foo";

    public EnvironmentStatTest() {
        envHome =  SharedTestUtils.getTestDir();
    }

    /**
     * Basic cache management stats.
     */
    @Test
    public void testCacheStats()
        throws Exception {

        /* Init the Environment. */
        EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setTransactional(true);
        envConfig.setConfigParam(EnvironmentParams.NODE_MAX.getName(), "6");
        envConfig.setAllowCreate(true);
        Environment env = new Environment(envHome, envConfig);

        EnvironmentStats stat = env.getStats(TestUtils.FAST_STATS);
        env.close();
        assertEquals(0, stat.getNCacheMiss());
        assertEquals(0, stat.getNNotResident());

        // Try to open and close again, now that the environment exists
        envConfig.setAllowCreate(false);
        env = new Environment(envHome, envConfig);

        /* Open a database and insert some data. */
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setTransactional(true);
        dbConfig.setAllowCreate(true);
        Database db = env.openDatabase(null, DB_NAME, dbConfig);
        db.put(null, new DatabaseEntry(new byte[0]),
                     new DatabaseEntry(new byte[0]));
        Transaction txn = env.beginTransaction(null, null);
        db.put(txn, new DatabaseEntry(new byte[0]),
                    new DatabaseEntry(new byte[0]));

        /* Do the check. */
        stat = env.getStats(TestUtils.FAST_STATS);
        MemoryBudget mb =
            DbInternal.getEnvironmentImpl(env).getMemoryBudget();

        assertEquals(mb.getCacheMemoryUsage(), stat.getCacheTotalBytes());

        /*
         * The size of each log buffer is calculated by:
         * mb.logBufferBudget/numBuffers, which is rounded down to the nearest
         * integer. The stats count the precise capacity of the log
         * buffers. Because of rounding down, the memory budget may be slightly
         * > than the stats buffer bytes, but the difference shouldn't be
         * greater than the numBuffers.
         */
        assertTrue((mb.getLogBufferBudget() - stat.getBufferBytes() <=
                    stat.getNLogBuffers()));
        assertEquals(mb.getTreeMemoryUsage() + mb.getTreeAdminMemoryUsage(),
                     stat.getDataBytes());
        assertEquals(mb.getLockMemoryUsage(), stat.getLockBytes());
        assertEquals(mb.getAdminMemoryUsage(), stat.getAdminBytes());

        assertTrue(stat.getBufferBytes() > 0);
        assertTrue(stat.getDataBytes() > 0);
        assertTrue(stat.getLockBytes() > 0);
        assertTrue(stat.getAdminBytes() > 0);

        /* Account for rounding down when calculating log buffer size. */
        assertTrue(stat.getCacheTotalBytes() -
                   (stat.getBufferBytes() +
                     stat.getDataBytes() +
                     stat.getLockBytes() +
                    stat.getAdminBytes()) <= stat.getNLogBuffers());

        assertEquals(11, stat.getNCacheMiss());
        assertEquals(11, stat.getNNotResident());

        /* Test deprecated getCacheDataBytes method. */
        final EnvironmentStats finalStat = stat;
        final long expectCacheDataBytes = mb.getCacheMemoryUsage() -
                                          mb.getLogBufferBudget();
        (new Runnable() {
            @Deprecated
            public void run() {
                assertTrue((expectCacheDataBytes -
                           finalStat.getCacheDataBytes()) <=
                               finalStat.getNLogBuffers());
            }
        }).run();

        txn.abort();
        db.close();
        env.close();
    }

    /**
     * Check stats to see if we correctly record nLogFsyncs (any fsync of the
     * log) and nFSyncs(commit fsyncs)
     */
    @Test
    public void testFSyncStats()
        throws Exception {

        /* The usual env and db setup */
        EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setTransactional(true);
        envConfig.setAllowCreate(true);
        Environment env = new Environment(envHome, envConfig);

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setTransactional(true);
        dbConfig.setAllowCreate(true);

        Database db = env.openDatabase(null, "foo", dbConfig);
        DatabaseEntry value = new DatabaseEntry();
        Transaction txn = env.beginTransaction(null, null);
        IntegerBinding.intToEntry(10, value);
        db.put(txn, value, value);

        StatsConfig statConfig = new StatsConfig();
        statConfig.setClear(true);
        /* Get a snapshot of the stats, for use as the starting point. */
        EnvironmentStats start = env.getStats(statConfig);

        /*
         * The call to env.sync() provokes a ckpt, which does a group mgr type
         * commit, so both getNFsyncs and nLogFSyncs are incremented.
         */
        env.sync();
        EnvironmentStats postSync = env.getStats(statConfig);
        assertEquals(1, postSync.getNFSyncs());
        assertEquals(1, postSync.getNLogFSyncs());

        /* Should be a transaction related fsync */
        txn.commitSync();
        EnvironmentStats postCommit = env.getStats(statConfig);
        assertEquals(1, postCommit.getNFSyncs());
        assertEquals(1, postCommit.getNLogFSyncs());

        /* Should be a transaction related fsync */
        DbInternal.getEnvironmentImpl(env).forceLogFileFlip();
        EnvironmentStats postFlip = env.getStats(statConfig);
        assertEquals(0, postFlip.getNFSyncs());
        assertEquals(1, postCommit.getNLogFSyncs());

        /* Call api to test that cast exception does not occur [#23060] */
        postFlip.getAvgBatchManual();

        db.close();
        env.close();
    }

    /*
     * Test that the Database.sync() and Database.close() won't do a fsync if
     * there is no updates on the database.
     */
    @Test
    public void testDbFSyncs()
        throws Exception {

        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(false);

        Environment env = new Environment(envHome, envConfig);

        /* Check the normal database. */
        checkCloseFSyncs(env, false);

        /* Flush a new log file to make sure the next check would succeed. */
        DbInternal.getEnvironmentImpl(env).forceLogFileFlip();

        /* Check the dw database. */
        checkCloseFSyncs(env, true);

        env.close();
    }

    private void checkCloseFSyncs(Environment env, boolean deferredWrite)
        throws Exception {

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setDeferredWrite(deferredWrite);

        Database[] dbs = new Database[1000];

        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        for (int i = 0; i < 1000; i++) {
            dbs[i] = env.openDatabase(null, "db" + i, dbConfig);
            IntegerBinding.intToEntry(i, key);
            StringBinding.stringToEntry("herococo", data);
            dbs[i].put(null, key, data);
        }

        StatsConfig stConfig = new StatsConfig();
        stConfig.setClear(true);

        EnvironmentStats envStats = env.getStats(stConfig);
        assertTrue(envStats.getNLogFSyncs() > 0);

        env.sync();

        envStats = env.getStats(stConfig);

        /*
         * The log file size is default 10M, 1000 records should be written in
         * the same log file.
         */
        assertTrue(envStats.getNLogFSyncs() == 1);

        if (deferredWrite) {
            for (int i = 0; i < 1000; i++) {
                dbs[i].sync();
            }
            envStats = env.getStats(stConfig);
            assertTrue(envStats.getNLogFSyncs() == 0);
        }

        for (int i = 0; i < 1000; i++) {
            dbs[i].close();
        }

        /*
         * Test no matter the database is deferred write or not, no log fsyncs
         * if no changes made before closing it.
         */
        envStats = env.getStats(stConfig);
        assert(envStats.getNLogFSyncs() == 0);
    }
}
