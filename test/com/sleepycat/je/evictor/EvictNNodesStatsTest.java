/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.evictor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sleepycat.je.CacheMode;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentStats;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.dbi.DbTree;
import com.sleepycat.je.dbi.MemoryBudget;
import com.sleepycat.je.tree.IN;
import com.sleepycat.je.txn.Txn;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.je.utilint.StatGroup;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/**
 * This tests exercises the act of eviction and determines whether the
 * expected nodes have been evicted properly.
 */
public class EvictNNodesStatsTest extends TestBase {

    private static final boolean DEBUG = false;
    private static final int BIG_CACHE_SIZE = 500000;
    private static final int SMALL_CACHE_SIZE = (int)
    MemoryBudget.MIN_MAX_MEMORY_SIZE;
    private final StatGroup placeholderMBStats = 
        new StatGroup("placeholder", "");

    private File envHome = null;
    private Environment env = null;
    private Database db = null;
    private int actualLNs = 0;
    private int actualINs = 0;

    public EvictNNodesStatsTest() {
        envHome = SharedTestUtils.getTestDir();
    }

    @Before
    public void setUp() 
        throws Exception {
        
        super.setUp();
        IN.ACCUMULATED_LIMIT = 0;
        Txn.ACCUMULATED_LIMIT = 0;
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
        envHome = null;
        env = null;
        db = null;
    }

    /**
     * Check that the counters of evicted MapLNs in the DB mapping tree and
     * the counter of evicted BINs in a regular DB eviction works.  [#13415]
     */
    @Test
    public void testRegularDB()
        throws DatabaseException {

        /* Initialize an environment and open a test DB. */
        openEnv(80, SMALL_CACHE_SIZE);

        EnvironmentStats stats;
        StatsConfig statsConfig = new StatsConfig();
        statsConfig.setClear(true);

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);

        DatabaseEntry entry = new DatabaseEntry(new byte[1]);
        OperationStatus status;

        /* Baseline mapping tree LNs and INs. */
        final int baseLNs = 2; // Utilization DB and test DB
        final int baseINs = 2; // Root IN and BIN
        checkMappingTree(baseLNs, baseINs);

        /*
         * Create enough DBs to fill up a BIN in the mapping DB.  NODE_MAX is
         * configured to be 4 in this test.  There are already 2 DBs open.
         */
        final int nDbs = 4;
        Database[] dbs = new Database[nDbs];
        for (int i = 0; i < nDbs; i += 1) {
            dbs[i] = env.openDatabase(null, "db" + i, dbConfig);
            status = dbs[i].put(null, entry, entry);
            assertSame(OperationStatus.SUCCESS, status);
            assertTrue(isRootResident(dbs[i]));
        }
        checkMappingTree(baseLNs + nDbs /*Add 1 MapLN per open DB*/,
                         baseINs + 1 /*Add 1 BIN in the mapping tree*/);

        /* Close DBs and force eviction. */
        for (int i = 0; i < nDbs; i += 1) {
            dbs[i].close();
        }

        forceEviction();
        /* Load Stats. */
        stats = env.getStats(statsConfig);

        assertEquals("Evicted MapLNs",
                     nDbs + 1, // nDbs and Utilization DB
                     stats.getNRootNodesEvicted());
        assertEquals("Evicted BINs",
                     nDbs + 4, // 2 BINs for Name DB, 1 for Mapping DB,
                               // 1 for Utilization DB and 1 per each nDb
                     stats.getNNodesExplicitlyEvicted());
        checkMappingTree(baseLNs, baseINs);

        /* 
         * Sneak in some testing of the stat getter calls. The actual
         * value we're comparing to is not that important, just updated them
         * if the test changes by printing System.out.println(stats) and
         * setting appropriate comparison vals. This is a way to make
         * sure the getter works.
         */
        assertEquals(0, stats.getNUpperINsEvictedCacheMode());
        assertEquals(0, stats.getNUpperINsEvictedManual());
        assertEquals(0, stats.getNUpperINsEvictedCritical());
        assertEquals(0, stats.getNUpperINsEvictedEvictorThread());
        assertEquals(0, stats.getNBINsEvictedCacheMode());
        assertEquals(0, stats.getNBINsEvictedManual());
        assertTrue(stats.getNBINsEvictedCritical() > 0);
        assertEquals(0, stats.getNBINsEvictedEvictorThread());
        assertTrue(stats.getNBINsFetch() > 0);
        assertEquals(0, stats.getNBINsFetchMiss());
        assertEquals(0, stats.getNUpperINsFetch());
        assertEquals(0, stats.getNUpperINsFetchMiss());
        assertEquals(0, stats.getNThreadUnavailable());
        assertTrue(stats.getNLNsFetch() > 0);
        assertEquals(0, stats.getNLNsFetchMiss());
        assertTrue(stats.getNCachedBINs() > 0);
        assertTrue(stats.getNCachedUpperINs() > 0);
        
        closeEnv();
    }

    /**
     * Check that the counters of evicted MapLNs in the DB mapping tree and
     * the counter of evicted BINs in a deferred write DB eviction works.
     * [#13415]
     */
    @Test
    public void testDeferredWriteDB()
        throws DatabaseException {

        /* Initialize an environment and open a test DB. */
        openEnv(80, SMALL_CACHE_SIZE);

        EnvironmentStats stats;
        StatsConfig statsConfig = new StatsConfig();
        statsConfig.setClear(true);

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);

        DatabaseEntry entry = new DatabaseEntry(new byte[1]);
        OperationStatus status;

        /* Baseline mapping tree LNs and INs. */
        final int baseLNs = 2; // Utilization DB and test DB
        final int baseINs = 2; // Root IN and BIN

        checkMappingTree(baseLNs, baseINs);

        /* Deferred write DBs have special rules. */
        dbConfig.setDeferredWrite(true);
        Database db2 = env.openDatabase(null, "db2", dbConfig);
        status = db2.put(null, entry, entry);
        assertSame(OperationStatus.SUCCESS, status);
        assertTrue(isRootResident(db2));
        checkMappingTree(baseLNs + 1, baseINs); // Deferred Write DB.

        /* Root eviction is disallowed if the root is dirty. */
        forceEviction();
        /* Load Stats. */
        stats = env.getStats(statsConfig);
        assertEquals("Evicted MapLNs",
                     1, // Utilization DB.
                     stats.getNRootNodesEvicted());
        assertEquals("Evicted BINs",
                     3, // 1 BIN for Name DB, 1 for Utilization DB,
                        // and 1 for Deferred Write DB.
                     stats.getNNodesExplicitlyEvicted());
        assertTrue(isRootResident(db2));
        checkMappingTree(baseLNs + 1, baseINs); // Deferred Write DB.

        db2.sync();
        forceEviction();
        /* Load Stats. */
        stats = env.getStats(statsConfig);
        assertEquals("Evicted MapLNs",
                     1, // Root eviction.
                     stats.getNRootNodesEvicted());
        assertEquals("Evicted BINs",
                     0,
                     stats.getNNodesExplicitlyEvicted());
        assertTrue(!isRootResident(db2));
        checkMappingTree(baseLNs + 1, baseINs); // Deferred Write DB.

        db2.close();
        forceEviction();
        /* Load Stats. */
        stats = env.getStats(statsConfig);
        assertEquals("Evicted MapLNs",
                     1, // Root eviction.
                     stats.getNRootNodesEvicted());
        assertEquals("Evicted BINs",
                     0,
                     stats.getNNodesExplicitlyEvicted());

        checkMappingTree(baseLNs + 1, baseINs); // Deferred Write DB.

        closeEnv();
    }

    private void forceEviction()
        throws DatabaseException {

        OperationStatus status;

        /*
         * Repeat twice to cause a 2nd pass over the INList.  The second pass
         * evicts BINs that were only stripped of LNs in the first pass.
         */
        for (int i = 0; i < 2; i += 1) {
            /* Fill up cache so as to call eviction. */
            status = db.put(null, new DatabaseEntry(new byte[1]),
                                  new DatabaseEntry(new byte[BIG_CACHE_SIZE]));
            assertSame(OperationStatus.SUCCESS, status);

            /* Do a manual call eviction. */
            env.evictMemory();

            status = db.delete(null, new DatabaseEntry(new byte[1]));
            assertSame(OperationStatus.SUCCESS, status);
        }
    }

    /**
     * Check for the expected number of nodes in the mapping DB.
     */
    private void checkMappingTree(int expectLNs, int expectINs)
        throws DatabaseException {

        IN root = DbInternal.getEnvironmentImpl(env).
            getDbTree().getDb(DbTree.ID_DB_ID).getTree().
            getRootIN(CacheMode.UNCHANGED);
        actualLNs = 0;
        actualINs = 0;
        countMappingTree(root);
        root.releaseLatch();
        assertEquals("LNs", expectLNs, actualLNs);
        assertEquals("INs", expectINs, actualINs);
    }

    private void countMappingTree(IN parent) {
        actualINs += 1;
        for (int i = 0; i < parent.getNEntries(); i += 1) {
            if (parent.getTarget(i) != null) {
                if (parent.getTarget(i) instanceof IN) {
                    countMappingTree((IN) parent.getTarget(i));
                } else {
                    actualLNs += 1;
                }
            }
        }
    }

    /**
     * Returns whether the root IN is currently resident for the given DB.
     */
    private boolean isRootResident(Database dbParam) {
        return DbInternal.getDatabaseImpl(dbParam).
                          getTree().
                          isRootResident();
    }

    /**
     * Open an environment and database.
     */
    private void openEnv(int floor,
                         int maxMem)
        throws DatabaseException {

        /* Convert floor percentage into bytes. */
        long evictBytes = maxMem - ((maxMem * floor) / 100);

        /* Make a non-txnal env w/no daemons and small nodes. */
        EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTxnNoSync(Boolean.getBoolean(TestUtils.NO_SYNC));
        envConfig.setConfigParam(EnvironmentParams.
                                 ENV_RUN_EVICTOR.getName(), "false");
        envConfig.setConfigParam(EnvironmentParams.
                                 ENV_RUN_INCOMPRESSOR.getName(), "false");
        envConfig.setConfigParam(EnvironmentParams.
                                 ENV_RUN_CLEANER.getName(), "false");
        envConfig.setConfigParam(EnvironmentParams.
                                 ENV_RUN_CHECKPOINTER.getName(), "false");
        envConfig.setConfigParam(EnvironmentParams.
                                 EVICTOR_EVICT_BYTES.getName(),
                                 (new Long(evictBytes)).toString());
        envConfig.setConfigParam(EnvironmentParams.
                                 MAX_MEMORY.getName(),
                                 new Integer(maxMem).toString());
        /* Enable DB (MapLN) eviction for eviction tests. */
        envConfig.setConfigParam(EnvironmentParams.
                                 ENV_DB_EVICTION.getName(), "true");


        /* Make small nodes */
        envConfig.setConfigParam(EnvironmentParams.
                                 NODE_MAX.getName(), "4");
        envConfig.setConfigParam(EnvironmentParams.
                                 NODE_MAX_DUPTREE.getName(), "4");
    
        env = new Environment(envHome, envConfig);

        /* Open a database. */
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        db = env.openDatabase(null, "foo", dbConfig);
    }

    private void closeEnv()
        throws DatabaseException {

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
