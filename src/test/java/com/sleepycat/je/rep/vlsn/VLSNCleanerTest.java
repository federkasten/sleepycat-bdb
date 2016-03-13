/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.vlsn;

import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Durability;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentStats;
import com.sleepycat.je.rep.RepInternal;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.impl.RepImpl;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/**
 * Exercise VLSNIndex and cleaning
 */
public class VLSNCleanerTest extends TestBase {
    private final boolean verbose = Boolean.getBoolean("verbose");
    private ReplicatedEnvironment master;
    private RepEnvInfo masterInfo;
    private Database db;
    private final File envRoot;

    public VLSNCleanerTest() {
        envRoot = SharedTestUtils.getTestDir();
    }

    @Test
    public void testBasic()
        throws DatabaseException {

        /*
         * Set the environment config to use very small files, have high
         * utilization, and permit manual cleaning.
         */
        EnvironmentConfig envConfig = new EnvironmentConfig();
        DbInternal.disableParameterValidation(envConfig);
        envConfig.setConfigParam(EnvironmentConfig.CLEANER_MIN_UTILIZATION,
                                 "90");
        envConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CLEANER,
                                 "false");
        envConfig.setConfigParam(EnvironmentConfig.LOG_FILE_MAX, "10000");
        envConfig.setTransactional(true);
        envConfig.setDurability(Durability.COMMIT_NO_SYNC);
        envConfig.setAllowCreate(true);

        masterInfo = RepTestUtils.setupEnvInfo(envRoot,
                                          envConfig,
                                          (short) 1,
                                          null);

        master = RepTestUtils.joinGroup(masterInfo);

        try {
            setupDatabase();
            int maxDeletions = 10;
            Environment env = master;
            EnvironmentStats stats;
            RepImpl repImpl = RepInternal.getRepImpl(master);

            do {
                stats = env.getStats(null);
                workAndClean();
                boolean anyCleaned = false;
                while (env.cleanLog() > 0) {
                    anyCleaned = true;
                    if (verbose) {
                        System.out.println("anyCleaned");
                    }
                }
                
                if (anyCleaned) {
                    CheckpointConfig force = new CheckpointConfig();
                    force.setForce(true);
                    env.checkpoint(force);
                }

                if (verbose) {
                    System.out.println("ckpt w/nCleanerDeletions=" +
                                       stats.getNCleanerDeletions());
                }
                assertTrue(repImpl.getVLSNIndex().verify(verbose));
            } while (stats.getNCleanerDeletions() < maxDeletions);
        } finally {
            if (db != null) {
                db.close();
            }

            if (master != null) {
                master.close();
            }
        }
    }

    private void setupDatabase()
        throws DatabaseException {

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setTransactional(true);
        dbConfig.setAllowCreate(true);
        db = master.openDatabase(null, "TEST", dbConfig);
    }

    private void workAndClean()
        throws DatabaseException {

        int WORK = 100;
        DatabaseEntry value = new DatabaseEntry();

        for (int i = 0; i < WORK; i++) {
            IntegerBinding.intToEntry(i, value);
            db.put(null, value, value);
            db.delete(null, value);
        }
    }
}
