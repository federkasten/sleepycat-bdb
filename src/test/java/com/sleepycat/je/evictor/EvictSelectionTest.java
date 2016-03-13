/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.evictor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentMutableConfig;
import com.sleepycat.je.EnvironmentStats;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.dbi.INList;
import com.sleepycat.je.dbi.MemoryBudget;
import com.sleepycat.je.evictor.Evictor.EvictionSource;
import com.sleepycat.je.evictor.TargetSelector.SetupInfo;
import com.sleepycat.je.tree.IN;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.je.utilint.TestHook;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

public class EvictSelectionTest extends TestBase {
    private final File envHome;
    private final int scanSize = 5;
    private Environment env;
    private EnvironmentImpl envImpl;

    public EvictSelectionTest() {
        envHome = SharedTestUtils.getTestDir();
    }

    @Override
    @After
    public void tearDown() {

        try {
            if (env != null) {
                env.close();
            }
        } catch (Throwable e) {
            System.out.println("tearDown: " + e);
        }
        env = null;
        envImpl = null;
    }

    @Test
    public void testEvictPass()
        throws Throwable {

        /* Create an environment, database, and insert some data. */
        initialize(true);

        /* The SharedEvictor is not testable using getExpectedCandidates. */
        if (env.getConfig().getSharedCache()) {
            env.close();
            env = null;
            return;
        }

        EnvironmentStats stats;
        StatsConfig statsConfig = new StatsConfig();
        statsConfig.setClear(true);

        /*
         * Set up the test w/a number of INs that doesn't divide evenly
         * into scan sets.
         */
        int startingNumINs = envImpl.getInMemoryINs().getSize();
        assertTrue((startingNumINs % scanSize) != 0);

        Evictor evictor = envImpl.getEvictor();
        EvictProfile testProfiler = new EvictProfile();
        evictor.setEvictProfileHook(testProfiler);
        /* Evict once to initialize the scan iterator. */
        evictor.evictBatch(EvictionSource.MANUAL, false, 1L);
        stats = env.getStats(statsConfig);

        /*
         * Test evictBatch, where each batch only evicts one node because
         * we are passing one byte for the currentRequiredEvictBytes
         * parameter.  To predict the evicted nodes when more than one
         * target is selected, we would have to simulate eviction and
         * maintain a parallel IN tree, which is too complex.
         */
        for (int batch = 1;; batch += 1) {

            List<Long> expectedCandidates = new ArrayList<Long>();
            int expectedNScanned = getExpectedCandidates
                (envImpl, evictor, expectedCandidates);

            evictor.evictBatch(EvictionSource.MANUAL, false, 1L);
            stats = env.getStats(statsConfig);

            assertEquals(1, stats.getNEvictPasses());
            assertEquals(expectedNScanned, stats.getNNodesScanned());

            List<Long> candidates = testProfiler.getCandidates();
            assertEquals(expectedCandidates, candidates);

            /* Stop when no more nodes are evictable. */
            if (expectedCandidates.isEmpty()) {
                break;
            }
        }

        env.close();
        env = null;
    }

    static class EvictProfile implements TestHook<IN> {
        /* Keep a list of candidate nodes. */
        private final List<Long> candidates = new ArrayList<Long>();

        /* Remember that this node was targeted. */
        public void doHook(IN target) {
            candidates.add(Long.valueOf(target.getNodeId()));
        }

        public List<Long> getCandidates() {
            return candidates;
        }

        public void hookSetup() {
            candidates.clear();
        }

        public void doIOHook() {};
        public void doHook() {}

        public IN getHookValue() {
            return null;
        };
    }

    /*
     * We might call evict on an empty INList if the cache is set very low
     * at recovery time.
     */
    @Test
    public void testEmptyINList()
        throws Throwable {

        /* Create an environment, database, and insert some data. */
        initialize(true);

        env.close();
        EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setCacheSize(MemoryBudget.MIN_MAX_MEMORY_SIZE);
        env = new Environment(envHome, envConfig);
        env.close();
        env = null;
    }

    /*
     * Create an environment, database, and insert some data.
     */
    private void initialize(boolean makeDatabase)
        throws DatabaseException {

        /* Environment */

        EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setAllowCreate(true);
        envConfig.setConfigParam(EnvironmentParams.
                                 ENV_RUN_EVICTOR.getName(),
                                 "false");
        envConfig.setConfigParam(EnvironmentParams.
                                 ENV_RUN_CLEANER.getName(),
                                 "false");
        envConfig.setConfigParam(EnvironmentParams.
                                 ENV_RUN_CHECKPOINTER.getName(),
                                 "false");
        envConfig.setConfigParam(EnvironmentParams.
                                 ENV_RUN_INCOMPRESSOR.getName(),
                                 "false");
        envConfig.setConfigParam(EnvironmentParams.
                                 NODE_MAX.getName(), "4");
        envConfig.setConfigParam(EnvironmentParams.
                                 EVICTOR_NODES_PER_SCAN.getName(), "5");

        env = new Environment(envHome, envConfig);
        envImpl = DbInternal.getEnvironmentImpl(env);

        if (makeDatabase) {
            /* Database */

            DatabaseConfig dbConfig = new DatabaseConfig();
            dbConfig.setAllowCreate(true);
            Database db = env.openDatabase(null, "foo", dbConfig);

            /* Insert enough keys to get an odd number of nodes */

            DatabaseEntry keyAndData = new DatabaseEntry();
            for (int i = 0; i < 110; i++) {
                IntegerBinding.intToEntry(i, keyAndData);
                db.put(null, keyAndData, keyAndData);
            }

            db.close();
        }
    }

    /**
     * Returns the number of INs selected (examined) and fills the expected
     * list with the selected targets.  Currently only one target is selected.
     */
    private int getExpectedCandidates(EnvironmentImpl envImpl,
                                      Evictor evictor,
                                      List<Long> expected) {
        if (!envImpl.getMemoryBudget().isTreeUsageAboveMinimum()) {
            return 0;
        }

        boolean evictByLruOnly = envImpl.getConfigManager().getBoolean
            (EnvironmentParams.EVICTOR_LRU_ONLY);
        INList inList = envImpl.getInMemoryINs();
        TargetSelector selector = evictor.getSelector();
        SetupInfo setupInfo =
            selector.startBatch(false /* doSpecialEviction */);
        Iterator<IN> inIter = selector.getScanIterator();
        IN firstScanned = null;
        boolean firstWrapped = false;

        long targetGeneration = Long.MAX_VALUE;
        int targetLevel = Integer.MAX_VALUE;
        boolean targetDirty = true;
        IN target = null;

        boolean wrapped = false;
        int nIterated = 0;

        int maxNodesToIterate = setupInfo.maxINsPerBatch;
        int nCandidates = 0;

        /* Simulate the eviction alorithm. */
        while (nIterated <  maxNodesToIterate && nCandidates < scanSize) {

            if (!inIter.hasNext()) {
                inIter = inList.iterator();
                wrapped = true;
            }

            IN in = inIter.next();
            nIterated += 1;

            if (firstScanned == null) {
                firstScanned = in;
                firstWrapped = wrapped;
            }

            if (in.getDatabase() == null || in.getDatabase().isDeleted()) {
                continue;
            }

            int evictType = in.getEvictionType();
            if (evictType == IN.MAY_NOT_EVICT) {
                continue;
            }

            if (evictByLruOnly) {
                if (in.getGeneration() < targetGeneration) {
                    targetGeneration = in.getGeneration();
                    target = in;
                }
            } else {
                int level = selector.normalizeLevel(in, evictType);
                if (targetLevel != level) {
                    if (targetLevel > level) {
                        targetLevel = level;
                        targetDirty = in.getDirty();
                        targetGeneration = in.getGeneration();
                        target = in;
                    }
                } else if (targetDirty != in.getDirty()) {
                    if (targetDirty) {
                        targetDirty = false;
                        targetGeneration = in.getGeneration();
                        target = in;
                    }
                } else {
                    if (targetGeneration > in.getGeneration()) {
                        targetGeneration = in.getGeneration();
                        target = in;
                    }
                }
            }

            nCandidates++;
        }

        /*
         * Restore the Evictor's iterator position to just before the
         * firstScanned IN.  There is no way to clone an iterator and we can't
         * create a tailSet iterator because the map is unsorted.
         */
        int prevPosition = 0;
        if (firstWrapped) {
            for (IN in : inList) {
                prevPosition += 1;
            }
        } else {
            boolean firstScannedFound = false;
            for (IN in : inList) {
                if (in == firstScanned) {
                    firstScannedFound = true;
                    break;
                } else {
                    prevPosition += 1;
                }
            }
            assertTrue(firstScannedFound);
        }
        inIter = inList.iterator();
        while (prevPosition > 0) {
            inIter.next();
            prevPosition -= 1;
        }
        selector.setScanIterator(inIter);

        /* Return the expected IN. */
        expected.clear();
        if (target != null) {
            expected.add(new Long(target.getNodeId()));
        }
        return nIterated;
    }

    /**
     * Tests a fix for an eviction bug that could cause an OOME in a read-only
     * environment.  [#17590]
     *
     * Before the bug fix, a dirty IN prevented eviction from working if the
     * dirty IN is returned by Evictor.selectIN repeatedly, only to be rejected
     * by Evictor.evictIN because it is dirty.  A dirty IN was considered as a
     * target and sometimes selected by selectIN as a way to avoid an infinite
     * loop when all INs are dirty.  This is unnecessary, since a condition was
     * added to cause the selectIN loop to terminate when all INs in the INList
     * have been iterated.  Now, with the fix, a dirty IN in a read-only
     * environment is never considered as a target or returned by selectIN.
     *
     * The OOME was reproduced with a simple test that uses a cursor to iterate
     * through 100k records, each 100k in size, in a read-only enviroment with
     * a 16m heap.  However, reproducing the problem in a fast-running unit
     * test is very difficult.  Instead, since the code change only impacts a
     * read-only environment, this unit test only ensures that the fix does not
     * cause an infinte loop when all nodes are dirty.
     */
    @Test
    public void testReadOnlyAllDirty()
        throws Throwable {

        /* Create an environment, database, and insert some data. */
        initialize(true /*makeDatabase*/);
        env.close();
        env = null;
        envImpl = null;

        /* Open the environment read-only. */
        final EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setReadOnly(true);
        env = new Environment(envHome, envConfig);
        envImpl = DbInternal.getEnvironmentImpl(env);

        /* Load everything into cache. */
        {
            final DatabaseConfig dbConfig = new DatabaseConfig();
            dbConfig.setReadOnly(true);
            final Database db = env.openDatabase(null, "foo", dbConfig);
            final Cursor cursor = db.openCursor(null, null);
            final DatabaseEntry key = new DatabaseEntry();
            final DatabaseEntry data = new DatabaseEntry();
            OperationStatus status = cursor.getFirst(key, data, null);
            while (status == OperationStatus.SUCCESS) {
                status = cursor.getNext(key, data, null);
            }
            cursor.close();
            db.close();
        }

        /* Artificially make all nodes dirty in a read-only environment. */
        for (IN in : envImpl.getInMemoryINs()) {
            in.setDirty(true);
        }

        /*
         * Force an eviction.  No nodes will be selected for an eviction,
         * because all nodes are dirty.  If the (nIterated < maxNodesToIterate)
         * condition is removed from the selectIN loop, an infinite loop will
         * occur.
         */
        final EnvironmentMutableConfig mutableConfig = env.getMutableConfig();
        mutableConfig.setCacheSize(MemoryBudget.MIN_MAX_MEMORY_SIZE);
        env.setMutableConfig(mutableConfig);
        final StatsConfig clearStats = new StatsConfig();
        clearStats.setClear(true);
        EnvironmentStats stats = env.getStats(clearStats);
        env.evictMemory();
        stats = env.getStats(clearStats);
        assertEquals(0, stats.getNNodesSelected());

        env.close();
        env = null;
        envImpl = null;
    }
}
