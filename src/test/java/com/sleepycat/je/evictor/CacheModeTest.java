/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.evictor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.EnumSet;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.CacheMode;
import com.sleepycat.je.CacheModeStrategy;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentMutableConfig;
import com.sleepycat.je.EnvironmentStats;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.dbi.DatabaseImpl;
import com.sleepycat.je.dbi.DbTree;
import com.sleepycat.je.dbi.MemoryBudget;
import com.sleepycat.je.tree.BIN;
import com.sleepycat.je.tree.IN;
import com.sleepycat.je.txn.BasicLocker;
import com.sleepycat.je.utilint.TestHook;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;
import com.sleepycat.je.util.TestUtils;

public class CacheModeTest extends TestBase {

    private final static Set<CacheMode> ALL_EXCEPT_DYNAMIC;
    static {
        ALL_EXCEPT_DYNAMIC = EnumSet.allOf(CacheMode.class);
        ALL_EXCEPT_DYNAMIC.remove(CacheMode.DYNAMIC);
    }

    /* Records occupy three BINs. */
    private static final int FIRST_REC = 0;
    private static final int LAST_REC = 7;
    private static final int NODE_MAX = 5;

    private File envHome;
    private Environment env;
    private Database db;
    private IN root;
    private BIN[] bins;
    private DatabaseEntry[] keys;

    public CacheModeTest() {
        envHome = SharedTestUtils.getTestDir();
    }

    @After
    public void tearDown() {
        
        if (env != null) {
            try {
                env.close();
            } catch (Throwable e) {
                System.out.println("tearDown: " + e);
            }
        }

        try {
            TestUtils.removeLogFiles("TearDown", envHome, false);
        } catch (Throwable e) {
            System.out.println("tearDown: " + e);
        }
        envHome = null;
        env = null;
        db = null;
        root = null;
        bins = null;
        keys = null;
    }

    private void open() {

        /* Open env, disable all daemon threads. */
        final EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setConfigParam("je.env.runCleaner", "false");
        envConfig.setConfigParam("je.env.runCheckpointer", "false");
        envConfig.setConfigParam("je.env.runINCompressor", "false");
        envConfig.setConfigParam("je.env.runEvictor", "false");
        env = new Environment(envHome, envConfig);

        /* Open db. */
        final DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setNodeMaxEntries(NODE_MAX);
        db = env.openDatabase(null, "foo", dbConfig);

        /*
         * Insert records.  Use a data size large enough to cause eviction when
         * testing EVICT_BIN mode.
         */
        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry(new byte[1 /*1024 * 50*/]);
        for (int i = FIRST_REC; i <= LAST_REC; i += 1) {
            IntegerBinding.intToEntry(i, key);
            db.put(null, key, data);
        }

        /* Sync to flush log buffer. */
        env.sync();

        /* Get root/parent IN in this two level tree. */
        root = DbInternal.getDatabaseImpl(db).
               getTree().getRootIN(CacheMode.UNCHANGED);
        root.releaseLatch();
        assertEquals(root.toString(), 3, root.getNEntries());

        /* Get BINs and first key in each BIN. */
        bins = new BIN[3];
        keys = new DatabaseEntry[3];
        for (int i = 0; i < 3; i += 1) {
            bins[i] = (BIN) root.getTarget(i);
            keys[i] = new DatabaseEntry();
            keys[i].setData(bins[i].getKey(0));
            //System.out.println("key " + i + ": " +
                               //IntegerBinding.entryToInt(keys[i]));
        }
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

    private void setMode(CacheMode mode) {
        EnvironmentMutableConfig envConfig = env.getMutableConfig();
        envConfig.setCacheMode(mode);
        env.setMutableConfig(envConfig);
    }

    private void setDynamicMode(CacheModeStrategy strategy) {
        EnvironmentMutableConfig envConfig = env.getMutableConfig();
        envConfig.setCacheMode(CacheMode.DYNAMIC);
        envConfig.setCacheModeStrategy(strategy);
        env.setMutableConfig(envConfig);
    }

    /**
     * Configure a tiny cache size and set a trap that fires an assertion when
     * eviction occurs.  This is used for testing EVICT_BIN and MAKE_COLD,
     * which should never cause critical eviction.
     */
    private void setEvictionTrap() {

        EnvironmentMutableConfig envConfig = env.getMutableConfig();
        envConfig.setCacheSize(MemoryBudget.MIN_MAX_MEMORY_SIZE);
        env.setMutableConfig(envConfig);

        class MyHook implements TestHook<Boolean> {
            public Boolean getHookValue() {
                fail("Eviction should not occur in EVICT_BIN mode");
                return false; /* For compiler, will never happen. */
            }
            public void hookSetup() {
                throw new UnsupportedOperationException();
            }
            public void doIOHook() {
                throw new UnsupportedOperationException();
            }
            public void doHook() {
                throw new UnsupportedOperationException();
            }
            public void doHook(Boolean obj) {
                throw new UnsupportedOperationException();                
            }
        }

        DbInternal.getEnvironmentImpl(env).getEvictor().setRunnableHook
            (new MyHook());
    }

    private void clearEvictionTrap() {
        DbInternal.getEnvironmentImpl(env).getEvictor().setRunnableHook(null);
    }

    private void readFirstAndLastRecord() {
        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry(new byte[1]);
        OperationStatus status;
        status = db.get(null, keys[0], data, null);
        assertSame(OperationStatus.SUCCESS, status);
        status = db.get(null, keys[2], data, null);
        assertSame(OperationStatus.SUCCESS, status);
    }

    /**
     * CacheMode.DEFAULT assigns next generation to BIN and all ancestors, does
     * not evict.
     */
    @Test
    public void testMode_DEFAULT() {
        open();

        setMode(CacheMode.DEFAULT);

        long[] gens = new long[3];
        gens[0] = bins[0].getGeneration();
        gens[1] = bins[1].getGeneration();
        gens[2] = bins[2].getGeneration();
        long rootGen = root.getGeneration();

        readFirstAndLastRecord();

        /* First and last BIN (and root) should have a new generation. */
        assertTrue(gens[0] < bins[0].getGeneration());
        assertTrue(gens[1] == bins[1].getGeneration());
        assertTrue(gens[2] < bins[2].getGeneration());
        assertTrue(rootGen < root.getGeneration());

        /* BINs should not be evicted. */
        assertNotNull(root.getTarget(0));
        assertNotNull(root.getTarget(1));
        assertNotNull(root.getTarget(2));

        /* LNs should not be evicted. */
        assertNotNull(bins[0].getTarget(0));
        assertNotNull(bins[1].getTarget(0));
        assertNotNull(bins[2].getTarget(0));

        close();
    }

    /**
     * CacheMode.UNCHANGED does not change generations, does not evict.
     */
    @Test
    public void testMode_UNCHANGED() {
        open();

        setMode(CacheMode.UNCHANGED);

        long[] gens = new long[3];
        gens[0] = bins[0].getGeneration();
        gens[1] = bins[1].getGeneration();
        gens[2] = bins[2].getGeneration();
        long rootGen = root.getGeneration();

        readFirstAndLastRecord();

        /* No generations should change. */
        assertTrue(gens[0] == bins[0].getGeneration());
        assertTrue(gens[1] == bins[1].getGeneration());
        assertTrue(gens[2] == bins[2].getGeneration());
        assertTrue(rootGen == root.getGeneration());

        /* BINs should not be evicted. */
        assertNotNull(root.getTarget(0));
        assertNotNull(root.getTarget(1));
        assertNotNull(root.getTarget(2));

        /* LNs should not be evicted. */
        assertNotNull(bins[0].getTarget(0));
        assertNotNull(bins[1].getTarget(0));
        assertNotNull(bins[2].getTarget(0));

        close();
    }

    /**
     * CacheMode.KEEP_HOT assigns max generation to BIN and all ancestors,
     * does not evict.
     */
    @Test
    public void testMode_KEEP_HOT() {
        open();

        setMode(CacheMode.KEEP_HOT);

        long[] gens = new long[3];
        gens[0] = bins[0].getGeneration();
        gens[1] = bins[1].getGeneration();
        gens[2] = bins[2].getGeneration();
        long rootGen = root.getGeneration();

        readFirstAndLastRecord();

        /* First and last BIN (and root) should have max generation. */
        assertTrue(Long.MAX_VALUE == bins[0].getGeneration());
        assertTrue(gens[1] == bins[1].getGeneration());
        assertTrue(Long.MAX_VALUE == bins[2].getGeneration());
        assertTrue(Long.MAX_VALUE == root.getGeneration());

        /* BINs should not be evicted. */
        assertNotNull(root.getTarget(0));
        assertNotNull(root.getTarget(1));
        assertNotNull(root.getTarget(2));

        /* LNs should not be evicted. */
        assertNotNull(bins[0].getTarget(0));
        assertNotNull(bins[1].getTarget(0));
        assertNotNull(bins[2].getTarget(0));

        close();
    }

    /**
     * CacheMode.MAKE_COLD assigns min generation to BIN but not to ancestors,
     * and also operates as EVICT_BIN if the cache is full.
     */
    @Test
    public void testMode_MAKE_COLD() {
        open();

        setMode(CacheMode.MAKE_COLD);

        /* First test with a large cache. No eviction should occur. */

        long[] gens = new long[3];
        gens[0] = bins[0].getGeneration();
        gens[1] = bins[1].getGeneration();
        gens[2] = bins[2].getGeneration();
        long rootGen = root.getGeneration();

        readFirstAndLastRecord();

        /* First and last BIN (but not root) should have zero generation. */
        assertTrue(0 == bins[0].getGeneration());
        assertTrue(gens[1] == bins[1].getGeneration());
        assertTrue(0 == bins[2].getGeneration());
        assertTrue(rootGen == root.getGeneration());

        /* BINs should not be evicted. */
        assertNotNull(root.getTarget(0));
        assertNotNull(root.getTarget(1));
        assertNotNull(root.getTarget(2));

        /* LNs should not be evicted. */
        assertNotNull(bins[0].getTarget(0));
        assertNotNull(bins[1].getTarget(0));
        assertNotNull(bins[2].getTarget(0));

        /*
         * With a small cache configured by setEvictionTrap, eviction should
         * occur and the behavior should be the same as with EVICT_BIN.
         */
        setEvictionTrap();
        checkBinEviction();
        clearEvictionTrap();

        /* Bump cache size back up to a reasonable amount. */
        EnvironmentMutableConfig envConfig = env.getMutableConfig();
        envConfig.setCacheSize(64 * 1024 * 1024);
        env.setMutableConfig(envConfig);

        /* Now only LNs should be evicted. */
        readFirstAndLastRecord();

        /* First and last BIN (but not root) should have zero generation. */
        assertTrue(0 == bins[0].getGeneration());
        assertTrue(gens[1] == bins[1].getGeneration());
        assertTrue(0 == bins[2].getGeneration());
        assertTrue(rootGen == root.getGeneration());

        /* BINs should not be evicted. */
        assertNotNull(root.getTarget(0));
        assertNotNull(root.getTarget(1));
        assertNotNull(root.getTarget(2));

        /* LNs should be evicted. */
        assertNull(bins[0].getTarget(0));
        assertNotNull(bins[1].getTarget(0));
        assertNull(bins[2].getTarget(0));

        close();
    }

    /**
     * CacheMode.EVICT_LN assigns min generation to BIN but not to ancestors.
     *
     * evicts LN, but does not evict BIN.
     */
    @Test
    public void testMode_EVICT_LN() {
        open();

        setMode(CacheMode.EVICT_LN);

        long[] gens = new long[3];
        gens[0] = bins[0].getGeneration();
        gens[1] = bins[1].getGeneration();
        gens[2] = bins[2].getGeneration();
        long rootGen = root.getGeneration();

        readFirstAndLastRecord();

        /* First and last BIN (and root) should have a new generation. */
        assertTrue(gens[0] < bins[0].getGeneration());
        assertTrue(gens[1] == bins[1].getGeneration());
        assertTrue(gens[2] < bins[2].getGeneration());
        assertTrue(rootGen < root.getGeneration());

        /* BINs should not be evicted. */
        assertNotNull(root.getTarget(0));
        assertNotNull(root.getTarget(1));
        assertNotNull(root.getTarget(2));

        /* LNs should be evicted. */
        assertNull(bins[0].getTarget(0));
        assertNotNull(bins[1].getTarget(0));
        assertNull(bins[2].getTarget(0));

        close();
    }

    /**
     * CacheMode.EVICT_BIN does not change generation of BIN ancestors, evicts
     * BIN (and its LNs).
     */
    @Test
    public void testMode_EVICT_BIN() {
        open();

        setMode(CacheMode.EVICT_BIN);

        setEvictionTrap();
        checkBinEviction();
        clearEvictionTrap();

        close();
    }

    /**
     * Common method for checking EVICT_BIN behavior as well as MAKE_COLD when
     * cache is full.
     */
    private void checkBinEviction() {
        long[] gens = new long[3];
        gens[0] = bins[0].getGeneration();
        gens[1] = bins[1].getGeneration();
        gens[2] = bins[2].getGeneration();
        long rootGen = root.getGeneration();

        readFirstAndLastRecord();

        /* Middle BIN and root should have same generation. */
        assertTrue(gens[1] == bins[1].getGeneration());
        assertTrue(rootGen == root.getGeneration());

        /* BINs should be evicted. */
        assertNull(root.getTarget(0));
        assertNotNull(root.getTarget(1));
        assertNull(root.getTarget(2));
    }

    /**
     * CacheMode.DYNAMIC causes the CacheMode to be retrieved via the
     * CacheModeStrategy object for each operation.
     */
    @Test
    public void testMode_DYNAMIC() {
        open();

        final CacheMode[] modes = new CacheMode[] {
            CacheMode.KEEP_HOT,
            CacheMode.DEFAULT,
            CacheMode.MAKE_COLD,
            CacheMode.EVICT_LN,
        };
        IncrementingCacheMode strategy = new IncrementingCacheMode(modes);
        setDynamicMode(strategy);

        Cursor cursor = db.openCursor(null, null);
        assertSame(CacheMode.DYNAMIC, cursor.getCacheMode());
        
        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        OperationStatus status;

        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.KEEP_HOT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());

        status = cursor.getNext(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.DEFAULT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());

        status = cursor.getCurrent(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.MAKE_COLD,
                   DbInternal.getCursorImpl(cursor).getCacheMode());

        status = cursor.getLast(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.EVICT_LN,
                   DbInternal.getCursorImpl(cursor).getCacheMode());

        cursor.close();
        close();
    }

    @Test
    public void testEvictLnOnlyWhenMovingAway() {
        doEvictLnOnlyWhenMovingAway(false);
    }

    @Test
    public void testEvictLnNonCloning() {
        doEvictLnOnlyWhenMovingAway(true);
    }

    /**
     * CacheMode.EVICT_LN does not evict the LN when two consecutive Cursor
     * operations end up on the same record.
     */
    private void doEvictLnOnlyWhenMovingAway(boolean nonCloning) {
        open();

        setMode(CacheMode.EVICT_LN);

        Cursor cursor = db.openCursor(null, null);
        if (nonCloning) {
            DbInternal.setNonCloning(cursor, true);
        }
        assertSame(CacheMode.EVICT_LN, cursor.getCacheMode());

        /*
         * Examine the NNotResident stat to ensure that a node is not evicted
         * and then fetched by a single operation that doesn't move the cursor.
         */
        final StatsConfig clearStats = new StatsConfig();
        clearStats.setClear(true);
        EnvironmentStats stats = env.getStats(clearStats);

        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        OperationStatus status;

        /* Find 1st record resident. */
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertNotNull(bins[0].getTarget(0));
        stats = env.getStats(clearStats);
        assertEquals(0, stats.getNNotResident());

        /* Find 2nd record resident, evict 1st. */
        status = cursor.getNext(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertNotNull(bins[0].getTarget(1));
        assertNull(bins[0].getTarget(0));
        stats = env.getStats(clearStats);
        assertEquals(0, stats.getNNotResident());

        /* Fetch 1st, evict 2nd. */
        status = cursor.getPrev(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertNotNull(bins[0].getTarget(0));
        assertNull(bins[0].getTarget(1));
        stats = env.getStats(clearStats);
        assertEquals(1, stats.getNNotResident());

        /* Fetch 2nd, evict 1st. */
        status = cursor.getNext(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertNull(bins[0].getTarget(0));
        assertNotNull(bins[0].getTarget(1));
        stats = env.getStats(clearStats);
        assertEquals(1, stats.getNNotResident());

        /* Fetch 1st, evict 2nd. */
        status = cursor.getPrev(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertNotNull(bins[0].getTarget(0));
        assertNull(bins[0].getTarget(1));
        stats = env.getStats(clearStats);
        assertEquals(1, stats.getNNotResident());

        /*
         * With a non-cloning cursor, if we attempt an operation that may move
         * the cursor, we will always evict the LN because there is no dup
         * cursor to compare with, to see if the position has changed.  This is
         * an expected drawback of using a non-cloning cursor.
         */
        final int expectFetchWithoutPositionChange = nonCloning ? 1 : 0;

        /* No fetch needed to access 1st again. */
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertNotNull(bins[0].getTarget(0));
        stats = env.getStats(clearStats);
        assertEquals(expectFetchWithoutPositionChange,
                     stats.getNNotResident());

        /*
         * No fetch needed to access 1st again.  Note that no fetch occurs here
         * even with a non-cloning cursor, because getCurrent cannot move the
         * cursor.
         */
        status = cursor.getCurrent(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertNotNull(bins[0].getTarget(0));
        stats = env.getStats(clearStats);
        assertEquals(0, stats.getNNotResident());

        /* No fetch needed to access 1st again. */
        status = cursor.getSearchKey(keys[0], data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertNotNull(bins[0].getTarget(0));
        stats = env.getStats(clearStats);
        assertEquals(expectFetchWithoutPositionChange,
                     stats.getNNotResident());

        cursor.close();
        close();
    }

    /**
     * CacheMode.EVICT_BIN does not evict the BIN when two consecutive Cursor
     * operations end up on the same BIN.  If we stay on the same BIN but move
     * to a new LN, only the LN is evicted.  If we stay on the same LN, neither
     * LN nor BIN is evicted.
     */
    @Test
    public void testEvictBinOnlyWhenMovingAway() {
        open();

        setMode(CacheMode.EVICT_BIN);
        setEvictionTrap();

        Cursor cursor = db.openCursor(null, null);
        assertSame(CacheMode.EVICT_BIN, cursor.getCacheMode());

        /*
         * Examine the NNotResident stat to ensure that a node is not evicted
         * and then fetched by a single operation that doesn't move the cursor.
         */
        final StatsConfig clearStats = new StatsConfig();
        clearStats.setClear(true);
        EnvironmentStats stats = env.getStats(clearStats);

        final int firstKeyInSecondBin = IntegerBinding.entryToInt(keys[1]);
        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        OperationStatus status;

        /* Find records in 1st BIN resident. */
        for (int i = FIRST_REC; i < firstKeyInSecondBin; i += 1) {
            status = cursor.getNext(key, data, null);
            assertSame(OperationStatus.SUCCESS, status);
            assertEquals(i, IntegerBinding.entryToInt(key));
            assertSame(bins[0], DbInternal.getCursorImpl(cursor).getBIN());
            assertSame(bins[0], root.getTarget(0));
            assertNotNull(bins[0].getTarget(i));
            stats = env.getStats(clearStats);
            assertEquals(0, stats.getNNotResident());
            /* Find prior LN evicted. */
            if (i > 0) {
                assertNull(bins[0].getTarget(i - 1));
            }
        }

        /* Move to 2nd BIN, find resident. */
        status = cursor.getNext(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(bins[1], DbInternal.getCursorImpl(cursor).getBIN());
        assertSame(bins[1], root.getTarget(1));
        assertNotNull(bins[1].getTarget(0));
        stats = env.getStats(clearStats);
        assertEquals(0, stats.getNNotResident());
        /* Find prior BIN evicted. */
        assertNull(root.getTarget(0));

        /* Move back to 1st BIN, fetch BIN and LN. */
        status = cursor.getPrev(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertNotNull(root.getTarget(0));
        assertNotSame(bins[0], root.getTarget(0));
        bins[0] = (BIN) root.getTarget(0);
        assertNotNull(bins[0].getTarget(firstKeyInSecondBin - 1));
        assertEquals(firstKeyInSecondBin - 1, IntegerBinding.entryToInt(key));
        stats = env.getStats(clearStats);
        assertEquals(2, stats.getNNotResident());
        /* Find next BIN evicted. */
        assertNull(root.getTarget(1));

        /* When not moving the cursor, nothing is evicted. */
        status = cursor.getCurrent(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        stats = env.getStats(clearStats);
        assertEquals(0, stats.getNNotResident());

        cursor.close();
        clearEvictionTrap();
        close();
    }

    /**
     * CacheMode can be set via the Environment, Database and Cursor
     * properties.  Database CacheMode overrides Environment CacheMode.  Cursor
     * CacheMode overrides Database and Environment CacheMode.
     */
    @Test
    public void testModeProperties() {
        open();

        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        OperationStatus status;

        /* Env property is not overridden. */
        setMode(CacheMode.KEEP_HOT);
        Cursor cursor = db.openCursor(null, null);
        assertSame(CacheMode.KEEP_HOT, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.KEEP_HOT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());

        /* Then overridden by cursor. */
        cursor.setCacheMode(CacheMode.EVICT_LN);
        assertSame(CacheMode.EVICT_LN, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.EVICT_LN,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();

        /* Env property does not apply to internal databases. */
        DbTree dbTree = DbInternal.getEnvironmentImpl(env).getDbTree();
        DatabaseImpl dbImpl = dbTree.getDb(DbTree.ID_DB_ID);
        BasicLocker locker =
            BasicLocker.createBasicLocker(DbInternal.getEnvironmentImpl(env));
        cursor = DbInternal.makeCursor(dbImpl, locker, null);
        assertSame(CacheMode.DEFAULT, cursor.getCacheMode());
        assertSame(CacheMode.DEFAULT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.getFirst(new DatabaseEntry(), new DatabaseEntry(), null);
        assertSame(CacheMode.DEFAULT, cursor.getCacheMode());
        assertSame(CacheMode.DEFAULT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();
        locker.operationEnd();
        dbTree.releaseDb(dbImpl);

        /* Env property overridden by db property. */
        final DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setCacheMode(CacheMode.MAKE_COLD);
        Database db2 = env.openDatabase(null, "foo2", dbConfig);
        cursor = db2.openCursor(null, null);
        assertSame(CacheMode.MAKE_COLD, cursor.getCacheMode());
        key.setData(new byte[1]);
        data.setData(new byte[1]);
        status = cursor.put(key, data);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.MAKE_COLD,
                   DbInternal.getCursorImpl(cursor).getCacheMode());

        /* Then overridden by cursor. */
        cursor.setCacheMode(CacheMode.EVICT_LN);
        assertSame(CacheMode.EVICT_LN, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.EVICT_LN,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();

        /* Opening another handle on the db will override the property. */
        dbConfig.setCacheMode(CacheMode.DEFAULT);
        Database db3 = env.openDatabase(null, "foo2", dbConfig);
        cursor = db3.openCursor(null, null);
        assertSame(CacheMode.DEFAULT, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.DEFAULT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();

        /* Open another handle, set mode to null, close immediately. */
        dbConfig.setCacheMode(null);
        Database db4 = env.openDatabase(null, "foo2", dbConfig);
        db4.close();
        /* Env default is now used. */
        cursor = db.openCursor(null, null);
        assertSame(CacheMode.KEEP_HOT, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.KEEP_HOT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();

        /* Set env property to null, DEFAULT is then used. */
        setMode(null);
        cursor = db3.openCursor(null, null);
        assertSame(CacheMode.DEFAULT, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.DEFAULT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();

        db3.close();
        db2.close();
        close();
    }

    /**
     * CacheModeStrategy can be set via the Environment and Database
     * properties.  Database CacheModeStrategy overrides Environment
     * CacheModeStrategy.
     */
    @Test
    public void testStrategyProperties() {
        open();

        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        OperationStatus status;

        /* Env property is not overridden. */
        setDynamicMode(new WrapperStrategy(CacheMode.KEEP_HOT));
        Cursor cursor = db.openCursor(null, null);
        assertSame(CacheMode.DYNAMIC, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.KEEP_HOT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());

        /* Then overridden by cursor. */
        cursor.setCacheMode(CacheMode.EVICT_LN);
        assertSame(CacheMode.EVICT_LN, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.EVICT_LN,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();

        /* Env property overridden by db property. */
        final DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setCacheMode(CacheMode.DYNAMIC);
        dbConfig.setCacheModeStrategy
            (new WrapperStrategy(CacheMode.MAKE_COLD));
        Database db2 = env.openDatabase(null, "foo2", dbConfig);
        cursor = db2.openCursor(null, null);
        assertSame(CacheMode.DYNAMIC, cursor.getCacheMode());
        key.setData(new byte[1]);
        data.setData(new byte[1]);
        status = cursor.put(key, data);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.MAKE_COLD,
                   DbInternal.getCursorImpl(cursor).getCacheMode());

        /* Then overridden by cursor. */
        cursor.setCacheMode(CacheMode.EVICT_LN);
        assertSame(CacheMode.EVICT_LN, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.EVICT_LN,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();

        /* Opening another handle on the db will override the property. */
        dbConfig.setCacheModeStrategy(new WrapperStrategy(CacheMode.DEFAULT));
        Database db3 = env.openDatabase(null, "foo2", dbConfig);
        cursor = db3.openCursor(null, null);
        assertSame(CacheMode.DYNAMIC, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.DEFAULT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();

        /* Open another handle, set mode to null, close immediately. */
        dbConfig.setCacheMode(null);
        Database db4 = env.openDatabase(null, "foo2", dbConfig);
        db4.close();
        /* Env default is now used. */
        cursor = db.openCursor(null, null);
        assertSame(CacheMode.DYNAMIC, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.KEEP_HOT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();

        /* Set env property to null, DEFAULT is then used. */
        setMode(null);
        cursor = db3.openCursor(null, null);
        assertSame(CacheMode.DEFAULT, cursor.getCacheMode());
        status = cursor.getFirst(key, data, null);
        assertSame(OperationStatus.SUCCESS, status);
        assertSame(CacheMode.DEFAULT,
                   DbInternal.getCursorImpl(cursor).getCacheMode());
        cursor.close();

        db3.close();
        db2.close();
        close();
    }

    private static class WrapperStrategy implements CacheModeStrategy {

        private final CacheMode cacheMode;

        WrapperStrategy(CacheMode mode) {
            cacheMode = mode;
        }

        public CacheMode getCacheMode() {
            return cacheMode;
        }
    }

    private static class IncrementingCacheMode implements CacheModeStrategy {

        private final CacheMode[] modes;
        private int i = -1;

        IncrementingCacheMode(CacheMode[] modes) {
            this.modes = modes;
        }

        public CacheMode getCacheMode() {
            i += 1;
            return modes[i];
        }
    }
}
