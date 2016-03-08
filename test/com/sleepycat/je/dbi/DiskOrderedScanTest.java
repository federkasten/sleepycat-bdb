/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.dbi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.BitSet;

import junit.framework.AssertionFailedError;

import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.CacheMode;
import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.DiskOrderedCursor;
import com.sleepycat.je.DiskOrderedCursorConfig;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.ForwardCursor;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.log.FileManager;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

public class DiskOrderedScanTest extends TestBase {
    private static final int N_RECS = 10000;
    private static final int ONE_MB = 1 << 20;

    private final File envHome;
    private Environment env;
    private Database db;

    public DiskOrderedScanTest() {
        envHome = SharedTestUtils.getTestDir();
    }

    @Test
    public void testScanArgChecks()
        throws Throwable {

        open(false, CacheMode.DEFAULT);
        writeData(false, N_RECS);
        ForwardCursor dos = db.openCursor(new DiskOrderedCursorConfig());
        int cnt = 0;
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();

        /* key must be non-null */
        try {
            dos.getNext(null, data, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException IAE) {
            // expected
        }

        /* data must be non-null */
        try {
            dos.getNext(key, null, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException IAE) {
            // expected
        }

        /* lockMode must be null or READ_UNCOMMITTED. */
        try {
            dos.getNext(key, data, LockMode.READ_COMMITTED);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException IAE) {
            // expected
        }

        dos.close();
        try {
            dos.close();
        } catch (IllegalStateException ISE) {
            fail("unexpected IllegalStateException");
        }
        close();
    }

    @Test
    public void testScanPermutations()
        throws Throwable {

        for (final boolean dups :
             new boolean[] { false, true }) {

            for (final int nRecs :
                 new int[] { 0, N_RECS }) {

                for (final CacheMode cacheMode :
                     new CacheMode[] { CacheMode.DEFAULT, CacheMode.EVICT_LN,
                                       CacheMode.EVICT_BIN }) {

                    for (final boolean keysOnly :
                         new boolean[] { false, true }) {

                        for (final long memoryLimit :
                             new long[] { Long.MAX_VALUE, ONE_MB}) {

                            for (final long lsnBatchSize :
                                 new long[] { Long.MAX_VALUE, 100 }) {

                                TestUtils.removeFiles("Setup", envHome,
                                                      FileManager.JE_SUFFIX);

                                try {
                                    doScan(dups, nRecs, cacheMode, keysOnly,
                                           memoryLimit, lsnBatchSize);
                                } catch (Throwable e) {
                                    if (!(e instanceof AssertionFailedError ||
                                          e instanceof RuntimeException)) {
                                        throw e;
                                    }
                                    /* Wrap with context info. */
                                    throw new RuntimeException
                                        ("scan failed with" +
                                         " dups=" + dups +
                                         " nRecs=" + nRecs +
                                         " cacheMode=" + cacheMode +
                                         " keysOnly=" + keysOnly +
                                         " memoryLimit=" + memoryLimit +
                                         " lsnBatchSize=" + lsnBatchSize,
                                         e);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks that a 3 level (or larger) Btree can be scanned.
     *
     * This test is disabled because it uses too large a data set and takes too
     * long to be run as part of the standard unit test suite.
     */
    public void XXtestLargeScan()
        throws Throwable {

        doScan(false /*dups*/, 5 * 1000 * 1000, CacheMode.DEFAULT,
               false /*keysOnly*/, 10L * ONE_MB, Long.MAX_VALUE);
    }

    private void doScan(final boolean dups,
                        final int nRecs,
                        final CacheMode cacheMode,
                        final boolean keysOnly,
                        final long memoryLimit,
                        final long lsnBatchSize)
        throws Throwable {

        open(dups, cacheMode);
        writeData(dups, nRecs);
        DiskOrderedCursorConfig dosConfig = new DiskOrderedCursorConfig();
        dosConfig.setKeysOnly(keysOnly);
        dosConfig.setInternalMemoryLimit(memoryLimit);
        dosConfig.setLSNBatchSize(lsnBatchSize);
        DiskOrderedCursor dos = db.openCursor(dosConfig);
        int cnt = 0;
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        int expectedCnt = dups ? (nRecs * 2) : nRecs;
        BitSet seenKeys = new BitSet(expectedCnt);
        while (dos.getNext(key, data, null) == OperationStatus.SUCCESS) {
            int k1 = entryToInt(key);
            int d1;
            if (keysOnly) {
                assertNull(data.getData());
                d1 = 0;
            } else {
                d1 = entryToInt(data);
            }
            if (dups) {
                boolean v1 = (k1 == (d1 * -1));
                if (!keysOnly) {
                    boolean v2 = (d1 == (-1 * (k1 + nRecs + nRecs)));
                    assertTrue(v1 ^ v2);
                }
            } else {
                if (!keysOnly) {
                    assertEquals(k1, (d1 * -1));
                }
            }
            seenKeys.set(k1);
            cnt++;
        }
        assertEquals(cnt, expectedCnt);
        assertEquals(seenKeys.cardinality(), nRecs);

        /* [#21282] getNext should return NOTFOUND if called again. */
        assertEquals(dos.getNext(key, data, null), OperationStatus.NOTFOUND);
        dos.close();
        close();

        /*
        System.out.println("iters " +
                           DbInternal.getDiskOrderedCursorImpl(dos).
                                      getNScannerIterations() + ' ' +
                           getName());
        */
    }

    @Test
    public void testInterruptedDiskOrderedScan()
        throws Throwable {

        open(false, CacheMode.DEFAULT);
        writeData(false, N_RECS);
        ForwardCursor dos = db.openCursor(new DiskOrderedCursorConfig());
        int cnt = 0;
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        assertTrue(dos.getNext(key, data, null) == OperationStatus.SUCCESS);
        assertEquals(dos.getDatabase(), db);
        int k1 = entryToInt(key);
        int d1 = entryToInt(data);
        assertTrue(k1 == (d1 * -1));
        DatabaseEntry key2 = new DatabaseEntry();
        DatabaseEntry data2 = new DatabaseEntry();
        assertTrue(dos.getCurrent(key2, data2, null) ==
                   OperationStatus.SUCCESS);
        int k2 = entryToInt(key2);
        int d2 = entryToInt(data2);
        assertTrue(k1 == k2 && d1 == d2);
        dos.close();
        try {
            dos.getCurrent(key2, data2, null);
            fail("expected IllegalStateException from getCurrent");
        } catch (IllegalStateException ISE) {
            // expected
        }

        try {
            dos.getNext(key2, data2, null);
            fail("expected IllegalStateException from getNext");
        } catch (IllegalStateException ISE) {
            // expected
        }

        close();
    }

    /*
     * Test that a delete of the record that the DiskOrderedCursor is pointing
     * to doesn't affect the DOS.
     */
    @Test
    public void testDeleteOneDuringScan()
        throws Throwable {

        open(false, CacheMode.DEFAULT);
        writeData(false, N_RECS);

        ForwardCursor dos = db.openCursor(new DiskOrderedCursorConfig());
        Cursor cursor = db.openCursor(null, null);
        int cnt = 0;
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        DatabaseEntry key2 = new DatabaseEntry();
        DatabaseEntry data2 = new DatabaseEntry();
        assertTrue(dos.getNext(key, data, null) == OperationStatus.SUCCESS);
        int k1 = entryToInt(key);
        int d1 = entryToInt(data);
        assertTrue(k1 == (d1 * -1));
        assertTrue(dos.getCurrent(key2, data2, null) ==
                   OperationStatus.SUCCESS);
        int k2 = entryToInt(key2);
        int d2 = entryToInt(data2);
        assertTrue(k1 == k2 && d1 == d2);
        assertTrue(cursor.getSearchKey(key, data, null) ==
                   OperationStatus.SUCCESS);
        cursor.delete();
        assertTrue(dos.getCurrent(key2, data2, null) ==
                   OperationStatus.SUCCESS);
        k2 = entryToInt(key2);
        d2 = entryToInt(data2);
        dos.close();
        cursor.close();
        close();
    }

    /**
     * Checks that a consumer thread performing deletions does not cause a
     * deadlock.  This failed prior to the use of DiskOrderedScanner. [#21667]
     */
    @Test
    public void testDeleteAllDuringScan()
        throws Throwable {

        open(false, CacheMode.DEFAULT);
        writeData(false, N_RECS);

        DiskOrderedCursorConfig config = new DiskOrderedCursorConfig();
        config.setQueueSize(10).setLSNBatchSize(10);

        DiskOrderedCursor dos = db.openCursor(config);
        DiskOrderedCursorImpl dosImpl =
            DbInternal.getDiskOrderedCursorImpl(dos);
        Cursor cursor = db.openCursor(null, null);

        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();

        /* Loop until queue is full. */
        while (dosImpl.remainingQueueCapacity() > 0) { }

        for (int cnt = 0; cnt < N_RECS; cnt += 1) {

            assertSame(OperationStatus.SUCCESS, dos.getNext(key, data, null));
            int k1 = entryToInt(key);
            int d1 = entryToInt(data);
            assertEquals(k1, d1 * -1);

            assertSame(OperationStatus.SUCCESS,
                       cursor.getSearchKey(key, data, LockMode.RMW));
            assertEquals(k1, entryToInt(key));
            assertEquals(d1, entryToInt(data));
            assertSame(OperationStatus.SUCCESS, cursor.delete());
        }

        assertSame(OperationStatus.NOTFOUND, cursor.getFirst(key, data, null));

        dos.close();
        cursor.close();
        close();
    }

    private void open(final boolean allowDuplicates, final CacheMode cacheMode)
        throws Exception {

        EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setConfigParam
            (EnvironmentParams.ENV_RUN_EVICTOR.getName(), "false");

        envConfig.setConfigParam
            (EnvironmentParams.ENV_RUN_INCOMPRESSOR.getName(), "false");

        envConfig.setTransactional(false);
        envConfig.setAllowCreate(true);
        env = new Environment(envHome, envConfig);

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setExclusiveCreate(false);
        dbConfig.setTransactional(false);
        dbConfig.setSortedDuplicates(allowDuplicates);
        dbConfig.setCacheMode(cacheMode);
        db = env.openDatabase(null, "testDb", dbConfig);
    }

    private void writeData(final boolean dups, int nRecs) {

        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        for (int i = 0; i < nRecs; i++) {
            IntegerBinding.intToEntry(i, key);
            IntegerBinding.intToEntry(i * -1, data);
            assertEquals(db.putNoOverwrite(null, key, data),
                         OperationStatus.SUCCESS);
            if (dups) {
                IntegerBinding.intToEntry(-1 * (i + nRecs + nRecs), data);
                assertEquals(db.putNoDupData(null, key, data),
                             OperationStatus.SUCCESS);
            }
        }

        /*
         * If the scanned data set is large enough, a checkpoint may be needed
         * to ensure all expected records are scanned.  It seems that a
         * checkpoint is needed on some machines but not others, probably
         * because the checkpointer thread gets more or less time.  Therefore,
         * to make the test more reliable we always do a checkpoint here.
         */
         env.checkpoint(new CheckpointConfig().setForce(true));
    }

    private int entryToInt(DatabaseEntry entry) {
        assertEquals(4, entry.getSize());
        return IntegerBinding.entryToInt(entry);
    }

    private void close()
        throws Exception {

        if (db != null) {
            db.close();
            db = null;
        }

        if (env != null) {
            env.close();
            env = null;
        }
    }
}
