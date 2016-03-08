/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.cleaner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;

import com.sleepycat.bind.tuple.IntegerBinding;
import org.junit.After;
import org.junit.Test;

import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentStats;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/**
 * Checks that the cleaner wakes up at certain times even when there is no
 * logging:
 *  - startup
 *  - change to minUtilization
 *  - DB remove/truncate
 */
public class WakeupTest extends TestBase {

    private static final int FILE_SIZE = 1000000;
    private static final String DB_NAME = "WakeupTest";

    private final File envHome;
    private Environment env;
    private Database db;
    
    public WakeupTest() {
        envHome = SharedTestUtils.getTestDir();
    }

    private void open(final boolean runCleaner) {
        final EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setAllowCreate(true);
        envConfig.setConfigParam(
            EnvironmentConfig.LOG_FILE_MAX, Integer.toString(FILE_SIZE));
        envConfig.setConfigParam(
            EnvironmentConfig.CLEANER_MIN_UTILIZATION, "50");
        envConfig.setConfigParam(
            EnvironmentConfig.ENV_RUN_CLEANER, runCleaner ? "true" : "false");
        env = new Environment(envHome, envConfig);

        final DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        db = env.openDatabase(null, DB_NAME, dbConfig);
    }

    private void close() {
        if (db != null) {
            db.close();
            db = null;
        }
        if (env != null) {
            env.close();
            env = null;
        }
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
        if (env != null) {
            try {
                env.close();
            } finally {
                env = null;
            }
        }
    }

    @Test
    public void testCleanAtStartup() {
        open(false /*runCleaner*/);
        writeFiles(0 /*nActive*/, 10 /*nObsolete*/);
        close();
        open(true /*runCleaner*/);
        expectBackgroundCleaning();
        close();
    }

    @Test
    public void testCleanAfterMinUtilizationChange() {
        open(true /*runCleaner*/);
        writeFiles(4 /*nActive*/, 2 /*nObsolete*/);
        expectNothingToClean();
        final EnvironmentConfig envConfig = env.getConfig();
        envConfig.setConfigParam(
            EnvironmentConfig.CLEANER_MIN_UTILIZATION, "90");
        env.setMutableConfig(envConfig);
        expectBackgroundCleaning();
        close();
    }

    @Test
    public void testCleanAfterDbRemoval() {
        open(true /*runCleaner*/);
        writeFiles(5 /*nActive*/, 0 /*nObsolete*/);
        expectNothingToClean();
        db.close();
        db = null;
        env.removeDatabase(null, DB_NAME);
        expectBackgroundCleaning();
        close();
    }

    @Test
    public void testCleanAfterDbTruncate() {
        open(true /*runCleaner*/);
        writeFiles(5 /*nActive*/, 0 /*nObsolete*/);
        expectNothingToClean();
        db.close();
        db = null;
        env.truncateDatabase(null, DB_NAME, false);
        expectBackgroundCleaning();
        close();
    }

    private void expectNothingToClean() {
        final int nFiles = env.cleanLog();
        assertEquals(0, nFiles);
    }

    private void expectBackgroundCleaning() {
        final long endTime = System.currentTimeMillis() + (30 * 1000);
        while (System.currentTimeMillis() < endTime) {
            final EnvironmentStats stats = env.getStats(null);
            if (stats.getNCleanerRuns() > 0) {
                return;
            }
        }
        close();
        fail("Cleaner did not run at startup");
    }

    private void writeFiles(final int nActive, final int nObsolete) {
        int key = 0;
        final DatabaseEntry keyEntry = new DatabaseEntry();
        final DatabaseEntry dataEntry = new DatabaseEntry(new byte[FILE_SIZE]);
        for (int i = 0; i < nActive; i += 1) {
            IntegerBinding.intToEntry(key, keyEntry);
            db.put(null, keyEntry, dataEntry);
            key += 1;
        }
        IntegerBinding.intToEntry(key, keyEntry);
        for (int i = 0; i <= nObsolete; i += 1) {
            db.put(null, keyEntry, dataEntry);
        }
        env.checkpoint(new CheckpointConfig().setForce(true));
    }
}
