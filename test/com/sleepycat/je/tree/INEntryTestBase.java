/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.tree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;

import org.junit.After;
import org.junit.Before;

import com.sleepycat.je.CacheMode;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.dbi.DatabaseImpl;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

public class INEntryTestBase extends TestBase {

    File envHome = SharedTestUtils.getTestDir();

    EnvironmentConfig envConfig;

    int nodeMaxEntries;

    short compactMaxKeyLength = 0;

    CacheMode cacheMode = CacheMode.DEFAULT;

    Environment env = null;

    protected static String DB_NAME = "TestDb";

    @Before
    public void setUp()  
        throws Exception {

        super.setUp();
        envConfig = TestUtils.initEnvConfig();
        envConfig.setAllowCreate(true);
        envConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CLEANER,
                                 "false");
        envConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CHECKPOINTER,
                                 "false");
        envConfig.setConfigParam(EnvironmentConfig.TREE_COMPACT_MAX_KEY_LENGTH,
                                 String.valueOf(compactMaxKeyLength));
        nodeMaxEntries = Integer.parseInt
            (envConfig.getConfigParam(EnvironmentConfig.NODE_MAX_ENTRIES));
        env = new Environment(envHome, envConfig);
    }

    @After
    public void tearDown() {
        env.close();
    }

    /* Assumes the test creates just one IN node. */
    protected void verifyINMemorySize(DatabaseImpl dbImpl) {
        BIN in = (BIN)(dbImpl.getTree().getFirstNode(cacheMode));
        in.releaseLatch();

        final IN lastNode = dbImpl.getTree().getLastNode(cacheMode);
        assertEquals(in, lastNode);
        assertTrue(in.verifyMemorySize());

        in.releaseLatch();
        TestUtils.validateNodeMemUsage(dbImpl.getEnvironmentImpl(), true);
    }

    protected Database createDb(String dbName,
                                int keySize,
                                int count,
                                boolean keyPrefixingEnabled) {
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setSortedDuplicates(false);
        dbConfig.setKeyPrefixing(keyPrefixingEnabled);
        Database db = env.openDatabase(null, dbName, dbConfig);
        final DatabaseImpl dbImpl = DbInternal.getDatabaseImpl(db);
        DatabaseEntry key = new DatabaseEntry();
        for (int i=0; i < count; i++) {
            key.setData(createByteVal(i, keySize));
            db.put(null, key, key);
            verifyINMemorySize(dbImpl);
        }
        return db;
    }

    protected Database createDb(String dbName,
                                int keySize,
                                int count) {
        return createDb(dbName, keySize, count, false);
    }

    protected Database createDupDb(String dbName,
                                   int keySize,
                                   int count) {
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setSortedDuplicates(false);
        Database db = env.openDatabase(null, dbName, dbConfig);
        final DatabaseImpl dbImpl = DbInternal.getDatabaseImpl(db);
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        for (int i=0; i < count; i++) {
            key.setData(new byte[0]);
            data.setData(createByteVal(i, keySize));
            db.put(null, key, data);
            verifyINMemorySize(dbImpl);
        }
        return db;
    }

    protected byte[] createByteVal(int val, int arrayLength) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(arrayLength);
        if (arrayLength >= 4) {
            byteBuffer.putInt(val);
        } else if (arrayLength >= 2) {
            byteBuffer.putShort((short) val);
        } else {
            byteBuffer.put((byte) val);
        }
        return byteBuffer.array();
    }

    /* Dummy test IN. */
    class TestIN extends IN {
        private int maxEntries;

        TestIN(int capacity) {
            maxEntries = capacity;
        }

        @Override
        protected int getCompactMaxKeyLength() {
            return compactMaxKeyLength;
        }

        @Override
        public int getMaxEntries() {
            return maxEntries;
        }
    }
}
