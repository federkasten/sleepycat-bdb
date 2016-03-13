/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2004, 2010 Oracle and/or its affiliates.  All rights reserved.
 *
 */
package com.sleepycat.je.recovery;

import static org.junit.Assert.assertEquals;

import java.util.HashSet;

import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.log.Trace;
import com.sleepycat.je.recovery.stepwise.TestData;
import com.sleepycat.je.util.TestUtils;

public class CheckSplitAuntTest extends CheckBase {

    private static final String DB_NAME = "simpleDB";

    @Test
    public void testSplitAunt()
        throws Throwable {

        EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        turnOffEnvDaemons(envConfig);
        envConfig.setConfigParam(EnvironmentParams.NODE_MAX.getName(),
                                 "4");
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setTransactional(true);

        EnvironmentConfig restartConfig = TestUtils.initEnvConfig();
        turnOffEnvDaemons(envConfig);
        envConfig.setConfigParam(EnvironmentParams.NODE_MAX.getName(),
                                 "4");
        envConfig.setTransactional(true);

        testOneCase(DB_NAME,
                    envConfig,
                    dbConfig,
                    new TestGenerator(true){
                        void generateData(Database db)
                            throws DatabaseException {
                            setupSplitData(db);
                        }
                    },
                    restartConfig,
                    new DatabaseConfig());

        /*
         * Now run the test in a stepwise loop, truncate after each
         * log entry. We start the steps before the inserts, so the base
         * expected set is empty.
         */
        HashSet<TestData> currentExpected = new HashSet<TestData>();
        if (TestUtils.runLongTests()) {
            stepwiseLoop(DB_NAME, envConfig, dbConfig, currentExpected,  0);
        }
    }

    private void setupSplitData(Database db)
        throws DatabaseException {

        setStepwiseStart();

        int max = 12;

        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();

        /* Populate a tree so it grows to 3 levels, then checkpoint. */
        for (int i = 0; i < max; i ++) {
            IntegerBinding.intToEntry(i*10, key);
            IntegerBinding.intToEntry(i*10, data);
            assertEquals(OperationStatus.SUCCESS, db.put(null, key, data));
        }

        CheckpointConfig ckptConfig = new CheckpointConfig();
        Trace.trace(DbInternal.getEnvironmentImpl(env), "First sync");
        env.sync();

        Trace.trace(DbInternal.getEnvironmentImpl(env), "Second sync");
        env.sync();

        Trace.trace(DbInternal.getEnvironmentImpl(env), "Third sync");
        env.sync();

        Trace.trace(DbInternal.getEnvironmentImpl(env), "Fourth sync");
        env.sync();

        Trace.trace(DbInternal.getEnvironmentImpl(env), "Fifth sync");
        env.sync();

        Trace.trace(DbInternal.getEnvironmentImpl(env), "Sync6");
        env.sync();

        Trace.trace(DbInternal.getEnvironmentImpl(env), "After sync");

        /* Add a key to dirty the left hand branch. */
        IntegerBinding.intToEntry(5, key);
        IntegerBinding.intToEntry(5, data);
        assertEquals(OperationStatus.SUCCESS, db.put(null, key, data));
        Trace.trace
            (DbInternal.getEnvironmentImpl(env), "After single key insert");

        ckptConfig.setForce(true);
        ckptConfig.setMinimizeRecoveryTime(true);
        env.checkpoint(ckptConfig);

        Trace.trace(DbInternal.getEnvironmentImpl(env), "before split");

        /* Add enough keys to split the right hand branch. */
        for (int i = 51; i < 57; i ++) {
            IntegerBinding.intToEntry(i, key);
            IntegerBinding.intToEntry(i, data);
            assertEquals(OperationStatus.SUCCESS, db.put(null, key, data));
        }

        Trace.trace(DbInternal.getEnvironmentImpl(env), "after split");
    }
}
