/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.dbi;

import java.io.File;

import org.junit.Test;

import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.util.DualTestCase;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.util.test.SharedTestUtils;

public class DbTreeTest extends DualTestCase {
    private final File envHome;

    public DbTreeTest() {
        envHome = SharedTestUtils.getTestDir();
    }

    @Test
    public void testDbLookup() throws Throwable {
        try {
            EnvironmentConfig envConfig = TestUtils.initEnvConfig();
            envConfig.setTransactional(true);
            envConfig.setConfigParam(EnvironmentParams.NODE_MAX.getName(), "6");
            envConfig.setAllowCreate(true);
            Environment env = create(envHome, envConfig);

            // Make two databases
            DatabaseConfig dbConfig = new DatabaseConfig();
            dbConfig.setTransactional(true);
            dbConfig.setAllowCreate(true);
            Database dbHandleAbcd = env.openDatabase(null, "abcd", dbConfig);
            Database dbHandleXyz = env.openDatabase(null, "xyz", dbConfig);

            // Can we get them back?
            dbConfig.setAllowCreate(false);
            Database newAbcdHandle = env.openDatabase(null, "abcd", dbConfig);
            Database newXyzHandle = env.openDatabase(null, "xyz", dbConfig);

            dbHandleAbcd.close();
            dbHandleXyz.close();
            newAbcdHandle.close();
            newXyzHandle.close();
            close(env);
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        }
    }
}
