/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;

import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.util.DualTestCase;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.util.test.SharedTestUtils;

/**
 * Tests the read-committed (degree 2) isolation level.
 */
public class ReadCommittedTest extends DualTestCase {

    private final File envHome;
    private Environment env;
    private Database db;

    public ReadCommittedTest() {
        envHome = SharedTestUtils.getTestDir();
    }

    private void open()
        throws DatabaseException {

        EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        /* Control over isolation level is required by this test. */
        TestUtils.clearIsolationLevel(envConfig);

        envConfig.setTransactional(true);
        envConfig.setAllowCreate(true);
        env = create(envHome, envConfig);

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setTransactional(true);
        dbConfig.setAllowCreate(true);
        dbConfig.setExclusiveCreate(true);
        db = env.openDatabase(null, "foo", dbConfig);

        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();

        for (int i = 100; i <= 200; i += 100) {
            for (int j = 1; j <= 5; j += 1) {
                IntegerBinding.intToEntry(i + j, key);
                IntegerBinding.intToEntry(0, data);
                db.put(null, key, data);
            }
        }
    }

    private void close()
        throws DatabaseException {

        db.close();
        db = null;
        close(env);
        env = null;
    }

    @Test
    public void testIllegalConfig()
        throws DatabaseException {

        open();

        CursorConfig cursConfig;
        TransactionConfig txnConfig;

        /* Disallow transaction ReadCommitted and Serializable. */
        txnConfig = new TransactionConfig();
        txnConfig.setReadCommitted(true);
        txnConfig.setSerializableIsolation(true);
        try {
            env.beginTransaction(null, txnConfig);
            fail();
        } catch (IllegalArgumentException expected) {}

        /* Disallow transaction ReadCommitted and ReadUncommitted. */
        txnConfig = new TransactionConfig();
        txnConfig.setReadCommitted(true);
        txnConfig.setReadUncommitted(true);
        try {
            env.beginTransaction(null, txnConfig);
            fail();
        } catch (IllegalArgumentException expected) {}

        /* Disallow cursor ReadCommitted and ReadUncommitted. */
        cursConfig = new CursorConfig();
        cursConfig.setReadCommitted(true);
        cursConfig.setReadUncommitted(true);
        Transaction txn = env.beginTransaction(null, null);
        try {
            db.openCursor(txn, cursConfig);
            fail();
        } catch (IllegalArgumentException expected) {}
        txn.abort();

        close();
    }

    @Test
    public void testWithTransactionConfig()
        throws DatabaseException {

        doTestWithTransactionConfig(false /*nonCloning*/);
    }

    @Test
    public void testNonCloningWithTransactionConfig()
        throws DatabaseException {

        doTestWithTransactionConfig(true /*nonCloning*/);
    }

    private void doTestWithTransactionConfig(boolean nonCloning)
        throws DatabaseException {

        open();

        TransactionConfig config = new TransactionConfig();
        config.setReadCommitted(true);
        Transaction txn = env.beginTransaction(null, config);
        Cursor cursor = db.openCursor(txn, null);
        if (nonCloning) {
            cursor.setNonCloning(true);
        }

        checkReadCommitted(cursor, 100, true);

        cursor.close();
        txn.commit();
        close();
    }

    @Test
    public void testWithCursorConfig()
        throws DatabaseException {

        doTestWithCursorConfig(false /*nonCloning*/);
    }

    @Test
    public void testNonCloningWithCursorConfig()
        throws DatabaseException {

        doTestWithCursorConfig(true /*nonCloning*/);
    }

    private void doTestWithCursorConfig(boolean nonCloning) {

        open();

        Transaction txn = env.beginTransaction(null, null);
        CursorConfig config = new CursorConfig();
        config.setReadCommitted(true);
        Cursor cursor = db.openCursor(txn, config);
        if (nonCloning) {
            cursor.setNonCloning(true);
        }
        Cursor degree3Cursor = db.openCursor(txn, null);

        checkReadCommitted(cursor, 100, true);
        checkReadCommitted(degree3Cursor, 200, false);

        degree3Cursor.close();
        cursor.close();
        txn.commit();
        close();
    }

    @Test
    public void testWithLockMode()
        throws DatabaseException {

        open();

        Transaction txn = env.beginTransaction(null, null);

        checkReadCommitted(txn, LockMode.READ_COMMITTED, 100, true);
        checkReadCommitted(txn, null, 200, false);

        txn.commit();
        close();
    }

    /**
     * Checks that the given cursor provides the given
     * expectReadLocksAreReleased behavior.
     */
    private void checkReadCommitted(Cursor cursor,
                                    int startKey,
                                    boolean expectReadLocksAreReleased)
        throws DatabaseException {

        EnvironmentStats baseStats = env.getStats(null);
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();

        checkNReadLocks(baseStats, 0);
        for (int i = 1; i <= 5; i += 1) {
            IntegerBinding.intToEntry(startKey + i, key);
            OperationStatus status = cursor.getSearchKey(key, data, null);
            assertEquals(OperationStatus.SUCCESS, status);
            if (expectReadLocksAreReleased) {
                /* Read locks are released as the cursor moves. */
                checkNReadLocks(baseStats, 1);
            } else {
                /* Read locks are not released. */
                checkNReadLocks(baseStats, i);
            }
        }

        checkNWriteLocks(baseStats, 0);
        for (int i = 1; i <= 5; i += 1) {
            IntegerBinding.intToEntry(startKey + i, key);
            IntegerBinding.intToEntry(0, data);
            cursor.put(key, data);
            /* Write locks are not released. */
            if (expectReadLocksAreReleased) {
                /* A single new write lock, no upgrade. */
                checkNWriteLocks(baseStats, i);
            } else {
                /* Upgraded lock plus new write lock. */
                checkNWriteLocks(baseStats, i * 2);
            }
        }

        if (expectReadLocksAreReleased) {
            /* The last read lock was released by the put() call above. */
            checkNReadLocks(baseStats, 0);
        }
    }

    /**
     * Checks that the given lock mode provides the given
     * expectReadLocksAreReleased behavior.
     */
    private void checkReadCommitted(Transaction txn,
                                    LockMode lockMode,
                                    int startKey,
                                    boolean expectReadLocksAreReleased)
        throws DatabaseException {

        EnvironmentStats baseStats = env.getStats(null);
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();

        checkNReadLocks(baseStats, 0);
        for (int i = 1; i <= 5; i += 1) {
            IntegerBinding.intToEntry(startKey + i, key);
            OperationStatus status = db.get(txn, key, data, lockMode);
            assertEquals(OperationStatus.SUCCESS, status);
            if (expectReadLocksAreReleased) {
                /* Read locks are released when the cursor is closed. */
                checkNReadLocks(baseStats, 0);
            } else {
                /* Read locks are not released. */
                checkNReadLocks(baseStats, i);
            }
        }

        checkNWriteLocks(baseStats, 0);
        for (int i = 1; i <= 5; i += 1) {
            IntegerBinding.intToEntry(startKey + i, key);
            IntegerBinding.intToEntry(0, data);
            db.put(txn, key, data);
            /* Write locks are not released. */
            if (expectReadLocksAreReleased) {
                /* A single new write lock, no upgrade. */
                checkNWriteLocks(baseStats, i);
            } else {
                /* Upgraded lock plus new write lock. */
                checkNWriteLocks(baseStats, i * 2);
            }
        }

        if (expectReadLocksAreReleased) {
            /* The last read lock was released by the put() call above. */
            checkNReadLocks(baseStats, 0);
        }
    }

    private void checkNReadLocks(EnvironmentStats baseStats,
                                 int nReadLocksExpected)
        throws DatabaseException {

        EnvironmentStats stats = env.getStats(null);
        assertEquals
            ("Read locks -- ",
             nReadLocksExpected,
             stats.getNReadLocks() - baseStats.getNReadLocks());
    }

    private void checkNWriteLocks(EnvironmentStats baseStats,
                                  int nWriteLocksExpected)
        throws DatabaseException {

        EnvironmentStats stats = env.getStats(null);

        assertEquals
            ("Write locks -- ",
             nWriteLocksExpected,
             stats.getNWriteLocks() - baseStats.getNWriteLocks());
    }
}
