/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2010 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.cleaner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.CacheMode;
import com.sleepycat.je.CheckpointConfig;
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
import com.sleepycat.je.cleaner.UtilizationCalculator.TestAdjustment;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.log.UtilizationFileReader;
import com.sleepycat.je.util.DbSpace;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.je.utilint.TestHook;

/**
 * Tests the utilization correction algorithm in UtilizationCalculator.
 */
@RunWith(Parameterized.class)
public class UtilizationCorrectionTest extends CleanerTestBase {

    private static final boolean DEBUG = false;
    private static final String DB_NAME = "foo";

    /*
     * Writing RECORD_COUNT with LARGE_DATA should fill about 20 log files.
     * For some tests we write 2*RECORD_COUNT records, alternating large and
     * small.  The size of the data is designed to fit in cache, so we can test
     * the case where there is no eviction.
     */
    private static final int RECORD_COUNT = 2000;
    private static final int SMALL_DATA = 100;
    private static final int LARGE_DATA = 10000;
    private static final int FILE_SIZE = 1000000;
    private static final int MIN_LOG_FILES = 15;
    private static final double DELTA = 1e-15;
    
    private EnvironmentImpl envImpl;
    private UtilizationCalculator calculator;
    private Database db;
    private boolean evictLNs;
    private int minUtilization;
    private AtomicInteger nAdjustments = new AtomicInteger(0);
    private long lastCleanedFile = -1;

    @Parameters
    public static List<Object[]> genParams() {
        
        return getEnv(new boolean[] {false, true});
    }
    
    public UtilizationCorrectionTest(boolean multiSubDir) {
        envMultiSubDir =multiSubDir;
        customName = (envMultiSubDir) ? "multi-sub-dir" : null;
    }

    @After
    public void tearDown() 
        throws Exception {
        
        super.tearDown();
        db = null;
        env = null;
        envImpl = null;
        calculator = null;
        nAdjustments = null;
    }

    /**
     * Opens the environment and database.
     */
    private void openEnv() {

        openEnv(TestUtils.initEnvConfig());
    }

    private void openEnv(EnvironmentConfig envConfig) {

        envConfig.setAllowCreate(true);
        envConfig.setCacheMode
            (evictLNs ? CacheMode.EVICT_LN : CacheMode.DEFAULT);
        envConfig.setConfigParam(EnvironmentConfig.CLEANER_MIN_UTILIZATION,
                                 String.valueOf(minUtilization));
        envConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CLEANER, "false");
        envConfig.setConfigParam(EnvironmentConfig.ENV_RUN_EVICTOR, "false");
        envConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CHECKPOINTER,
                                 "false");
        envConfig.setConfigParam(EnvironmentConfig.ENV_RUN_IN_COMPRESSOR,
                                 "false");
        envConfig.setConfigParam(EnvironmentConfig.LOG_FILE_MAX,
                                 Integer.toString(FILE_SIZE));
        envConfig.setConfigParam
            (EnvironmentParams.CLEANER_CALC_MIN_UNCOUNTED_LNS.getName(), "25");

        if (envMultiSubDir) {
            envConfig.setConfigParam(EnvironmentConfig.LOG_N_DATA_DIRECTORIES,
                                     DATA_DIRS + "");
        }

        env = new Environment(envHome, envConfig);
        envImpl = DbInternal.getEnvironmentImpl(env);
        calculator = envImpl.getCleaner().getUtilizationCalculator();

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        db = env.openDatabase(null, DB_NAME, dbConfig);
    }

    /**
     * Closes the environment and database.
     */
    private void closeEnv() {

        if (db != null) {
            db.close();
            db = null;
        }
        if (env != null) {
            env.close();
            env = null;
            envImpl = null;
        }
    }

    /**
     * Close, open again, check that the LogSummary is persistent, close again.
     */
    private void closeEnvAndCheck() {

        final float correctionFactor1 = envImpl.getCleaner().
                                                getUtilizationCalculator().
                                                getLNSizeCorrectionFactor();
        final CleanerLogSummary logSummary1 =
            envImpl.getCleaner().getLogSummary();

        final EnvironmentStats stats1 = env.getStats(null);

        if (!evictLNs) {
            assertEquals(Float.NaN, stats1.getLNSizeCorrectionFactor(), DELTA);
        }

        /* For debugging. */
        //checkUtilization(correctionFactor1);

        closeEnv();
        openEnv();

        final float correctionFactor2 = envImpl.getCleaner().
                                                getUtilizationCalculator().
                                                getLNSizeCorrectionFactor();
        final CleanerLogSummary logSummary2 =
            envImpl.getCleaner().getLogSummary();

        final EnvironmentStats stats2 = env.getStats(null);

        assertEquals(logSummary1, logSummary2);
        assertEquals(correctionFactor1, correctionFactor2, DELTA);
        assertEquals
            (correctionFactor1, stats1.getLNSizeCorrectionFactor(), DELTA);
        assertEquals
            (correctionFactor1, stats2.getLNSizeCorrectionFactor(), DELTA);

        closeEnv();
    }

    /**
     * For debugging.
     */
    private void checkUtilization(float correctionFactor) {
        env.flushLog(false);
        final Map<Long,FileSummary> estMap =
            envImpl.getUtilizationProfile().getFileSummaryMap(true);
        final Map<Long, FileSummary> recalcMap =
            UtilizationFileReader.calcFileSummaryMap(envImpl);
        for (final Long file : estMap.keySet()) {
            final FileSummary estSummary = estMap.get(file);
            final FileSummary recalcSummary = recalcMap.get(file);
            if (recalcSummary == null) {
                System.out.printf("No recalc summary for file 0x%x\n", file);
                continue;
            }
            final int estUtil = estSummary.utilization();
            final int corUtil = estSummary.utilization(correctionFactor);
            final int recalcUtil = FileSummary.utilization
                (recalcSummary.getObsoleteSize(), estSummary.totalSize);
            if (Math.abs(corUtil - recalcUtil) >= 5) {
                System.out.printf
                    ("File 0x%x utilization estimated: %d corrected: %d" +
                     " recalc: %d\n", file, estUtil, corUtil, recalcUtil);
                System.out.println("est: " + estSummary);
                System.out.println("recalc: " + recalcSummary);
            }
        }
    }

    /**
     * Tests basic methods for estimating LN obsolete size.
     */
    @Test
    public void testBasicLNSizeCalculations() {
        FileSummary fs = new FileSummary();

        /* IN counting is not tested here. */
        fs.totalINCount = 0;
        fs.totalINSize = 0;
        fs.obsoleteINCount = 0;

        /* Use the same total counts and sizes for all tests below. */
        fs.totalCount = 1000;
        fs.totalSize = fs.totalCount * 100;
        fs.totalLNCount = fs.totalCount;
        fs.totalLNSize = fs.totalSize;

        /* Zero obsolete baseline. */
        fs.maxLNSize = 0;
        fs.obsoleteLNCount = 0;
        fs.obsoleteLNSize = 0;
        fs.obsoleteLNSizeCounted = 0;

        assertEquals(0, fs.getObsoleteSize());
        assertEquals(0, fs.getMaxObsoleteSize());
        assertEquals(100, FileSummary.utilization
                          (fs.getObsoleteSize(), fs.totalSize));
        assertEquals(100, FileSummary.utilization
                          (fs.getMaxObsoleteSize(), fs.totalSize));

        /* 1% obsolete, all sizes counted. */
        fs.maxLNSize = 100;
        fs.obsoleteLNCount = 10;
        fs.obsoleteLNSize = 10 * fs.maxLNSize;
        fs.obsoleteLNSizeCounted = 10;

        assertEquals(1000, fs.getObsoleteSize());
        assertEquals(1000, fs.getMaxObsoleteSize());
        assertEquals(99, FileSummary.utilization
                         (fs.getObsoleteSize(), fs.totalSize));
        assertEquals(99, FileSummary.utilization
                         (fs.getMaxObsoleteSize(), fs.totalSize));

        /*
         * 1% obsolete, but no sizes counted. The result is the same as above
         * because maxLNSize is the average size.
         */
        fs.maxLNSize = 100;
        fs.obsoleteLNCount = 10;
        fs.obsoleteLNSize = 0;
        fs.obsoleteLNSizeCounted = 0;

        assertEquals(1000, fs.getObsoleteSize());
        assertEquals(1000, fs.getMaxObsoleteSize());
        assertEquals(99, FileSummary.utilization
                         (fs.getObsoleteSize(), fs.totalSize));
        assertEquals(99, FileSummary.utilization
                         (fs.getMaxObsoleteSize(), fs.totalSize));

        /*
         * maxLNSize is greater than the average size, so the max obsolete
         * size is also larger and the utilization using max obsolete size is
         * smaller.
         */
        fs.maxLNSize = 1000;
        fs.obsoleteLNCount = 10;
        fs.obsoleteLNSize = 0;
        fs.obsoleteLNSizeCounted = 0;

        assertEquals(1000, fs.getObsoleteSize());
        assertEquals(10000, fs.getMaxObsoleteSize());
        assertEquals(99, FileSummary.utilization
                         (fs.getObsoleteSize(), fs.totalSize));
        assertEquals(90, FileSummary.utilization
                         (fs.getMaxObsoleteSize(), fs.totalSize));

        /*
         * maxLNSize is smaller than the average size, so the max obsolete
         * size is also smaller and the utilization using max obsolete size is
         * larger.
         */
        fs.maxLNSize = 50;
        fs.obsoleteLNCount = 10;
        fs.obsoleteLNSize = 0;
        fs.obsoleteLNSizeCounted = 0;

        assertEquals(1000, fs.getObsoleteSize());
        assertEquals(500, fs.getMaxObsoleteSize());
        assertEquals(99, FileSummary.utilization
                         (fs.getObsoleteSize(), fs.totalSize));
        assertEquals(100, FileSummary.utilization
                          (fs.getMaxObsoleteSize(), fs.totalSize));

        /*
         * Mixture of obsolete LNs with size counted and uncounted.  Those
         * counted are smaller than the average.
         */
        fs.maxLNSize = 200;
        fs.obsoleteLNCount = 30;
        fs.obsoleteLNSize = 500;
        fs.obsoleteLNSizeCounted = 10;

        assertEquals(2510, fs.getObsoleteSize());
        assertEquals(4500, fs.getMaxObsoleteSize());
        assertEquals(97, FileSummary.utilization
                         (fs.getObsoleteSize(), fs.totalSize));
        assertEquals(96, FileSummary.utilization
                         (fs.getMaxObsoleteSize(), fs.totalSize));

        /*
         * Here the max obsolete size (calculated by multiplying the maxLNSize
         * by the number of obsolete LNs whose size is uncounted) would be
         * greater than the greatest possible amount remaining after
         * subtracting the minimum LN log size (16 bytes) for each non-obsolete
         * LN from the total size.  The 92,000 figure is the correct maximum
         * and tests that we consider minimum LN sizes in the calculation.
         */
        fs.maxLNSize = 300;
        fs.obsoleteLNCount = 500;
        fs.obsoleteLNSize = 2500;
        fs.obsoleteLNSizeCounted = 50;

        assertEquals(48684, fs.getObsoleteSize());
        assertEquals(92000, fs.getMaxObsoleteSize());
        assertEquals(51, FileSummary.utilization
                         (fs.getObsoleteSize(), fs.totalSize));
        assertEquals(8, FileSummary.utilization
                        (fs.getMaxObsoleteSize(), fs.totalSize));
    }

    /**
     * With everything in cache, obsolete sizes are recorded correctly.
     * Estimated and true utilization are the same, around 0%, so the
     * correction factor is around 1.00.
     *
     * With minUtilization=40, cleaning will occur naturally and will correct
     * utilization.  An adjustment occurs for all log files because they are
     * all cleaned.
     *
     * forceLogFileFlip is not necessary because estimated utilization is
     * correct and the correction is always 1.0.
     */
    @Test
    public void testInCacheDeleteAll() {
        minUtilization = 40;
        evictLNs = false;
        openEnv();
        expectCorrection(0.99F, 1.01F);
        writeLargeDeleteAll();
        env.cleanLog();
        final EnvironmentStats stats = env.getStats(null);
        assertTrue("" + nAdjustments, nAdjustments.get() > MIN_LOG_FILES);
        assertEquals(nAdjustments.get(), stats.getNCleanerRuns());
        assertEquals(0, stats.getNCleanerProbeRuns());
        closeEnvAndCheck();
    }

    /**
     * With everything in cache, obsolete sizes are recorded correctly.
     * Estimated and true utilization are the same, around 99%, so the
     * correction factor is around 1.00.
     *
     * With minUtilization=60, cleaning does not occur naturally and no
     * adjustments occur.
     *
     * forceLogFileFlip is not necessary because estimated utilization is
     * correct and the correction is always 1.0.  Plus, there are no
     * adjustments.
     */
    @Test
    public void testInCacheDeleteSmall() {
        minUtilization = 60;
        evictLNs = false;
        openEnv();
        expectCorrection(0.99F, 1.01F);
        writeMixedDeleteSmall();
        env.cleanLog();
        final EnvironmentStats stats = env.getStats(null);
        assertEquals(0, nAdjustments.get());
        assertEquals(0, stats.getNCleanerRuns());
        assertEquals(0, stats.getNCleanerProbeRuns());
        closeEnvAndCheck();
    }

    /**
     * With everything in cache, obsolete sizes are recorded correctly.
     * Estimated and true utilization are the same, around 1%, so the
     * correction factor is around 1.00.
     *
     * With minUtilization=40, cleaning will occur naturally and will correct
     * utilization.  An adjustment occurs for all log files because they are
     * all cleaned.
     *
     * forceLogFileFlip is not necessary because estimated utilization is
     * correct and the correction is always 1.0.
     */
    @Test
    public void testInCacheDeleteLarge() {
        minUtilization = 40;
        evictLNs = false;
        openEnv();
        expectCorrection(0.99F, 1.01F);
        writeMixedDeleteLarge();
        env.cleanLog();
        final EnvironmentStats stats = env.getStats(null);
        assertTrue("" + nAdjustments, nAdjustments.get() > MIN_LOG_FILES);
        assertEquals(nAdjustments.get(), stats.getNCleanerRuns());
        assertEquals(0, stats.getNCleanerProbeRuns());
        closeEnvAndCheck();
    }

    /**
     * Only one record size is used, so even with EVICT_LN the estimated
     * average LN size is accurate.  Estimated and true utilization are the
     * same, around 0%, so the correction factor is around 1.00.
     *
     * With minUtilization=40, cleaning will occur naturally and will correct
     * utilization.  An adjustment occurs for all log files because they are
     * all cleaned.
     *
     * forceLogFileFlip is called after opening the database so the initial
     * INs/MapLNs/NameLNs don't change the utilization of other files.  Without
     * flipping, in this case, the first file would have estimated utilization
     * 4.8% and true utilization 0.06%, which is not the expected difference
     * for the other files which only contain user DB LNs.
     */
    @Test
    public void testEvictDeleteAll() {
        minUtilization = 40;
        evictLNs = true;
        openEnv();
        envImpl.forceLogFileFlip();
        expectCorrection(0.99F, 1.01F);
        writeLargeDeleteAll();
        env.cleanLog();
        final EnvironmentStats stats = env.getStats(null);
        assertTrue("" + nAdjustments, nAdjustments.get() > MIN_LOG_FILES);
        assertEquals(nAdjustments.get(), stats.getNCleanerRuns());
        assertEquals(0, stats.getNCleanerProbeRuns());
        closeEnvAndCheck();
    }

    /**
     * Only small records are deleted from a mix of large and small records.
     * Estimated utilization is 50%, true utilization is 98%, so the correction
     * factor is around 1.96.
     *
     * With minUtilization=60, cleaning will occur naturally and will correct
     * utilization.  Only one adjustment occurs because estimated utilization
     * is above minUtilization after the correction.
     *
     * forceLogFileFlip would cause cleaning of the first file, which would be
     * 50% utilized, and the correction would not be the same as the other
     * files which only contain user DB LNs.
     */
    @Test
    public void testEvictDeleteSmall() {
        minUtilization = 60;
        evictLNs = true;
        openEnv();
        expectCorrection(1.94F, 1.98F);
        writeMixedDeleteSmall();
        env.cleanLog();
        final EnvironmentStats stats = env.getStats(null);
        assertEquals(1, nAdjustments.get());
        assertEquals(1, stats.getNCleanerRuns());
        assertEquals(0, stats.getNCleanerProbeRuns());
        closeEnvAndCheck();
    }

    /**
     * Only large records are deleted from a mix of large and small records.
     * Estimated utilization is 50%, true utilization is 1%, so the correction
     * factor is around 0.02.
     *
     * With minUtilization=60, cleaning will occur naturally and will correct
     * utilization.  An adjustment occurs for all log files because they are
     * all cleaned.
     *
     * forceLogFileFlip would cause cleaning of the first file, which would be
     * 50% utilized, and the correction would not be the same as the other
     * files which only contain user DB LNs.
     */
    @Test
    public void testEvictDeleteLarge() {
        minUtilization = 60;
        evictLNs = true;
        openEnv();
        expectCorrection(0.01F, 0.03F);
        writeMixedDeleteLarge();
        env.cleanLog();
        final EnvironmentStats stats = env.getStats(null);
        assertTrue("" + nAdjustments, nAdjustments.get() > MIN_LOG_FILES);
        assertEquals(nAdjustments.get(), stats.getNCleanerRuns());
        assertEquals(0, stats.getNCleanerProbeRuns());
        closeEnvAndCheck();
    }
    
    /**
     * Larger records are deleted from a mix of variable size records, where
     * each log file contains records with a different base size.  Half the
     * records in a given log have the base size, and the other half have the
     * base size * 1.1.  Base sizes range from SMALL_DATA to LARGE_DATA.
     *
     * This checks that LN size adjustment works when the average LN size is
     * very different from one log files to another.  [#21106]
     *
     * Estimated utilization is 50%, true utilization is 47.5%, so the
     * correction factor is around 0.95.
     *
     * minUtilization=80 is used to cause cleaning of all files, to ensure
     * different sized LNs are treated correctly.  An adjustment occurs for all
     * log files because they are all cleaned.
     */
    @Test
    public void testEvictDeleteLargeVariableSizesByFile() {
        minUtilization = 80;
        evictLNs = true;
        openEnv();
        expectCorrection(0.94F, 0.96F);
        writeVariableSizesByFile(true /*deleteLarger*/);
        env.cleanLog();
        final EnvironmentStats stats = env.getStats(null);
        assertTrue("" + nAdjustments, nAdjustments.get() > MIN_LOG_FILES);
        assertEquals(nAdjustments.get(), stats.getNCleanerRuns());
        assertEquals(0, stats.getNCleanerProbeRuns());
        closeEnvAndCheck();
    }
    
    /**
     * Smaller records are deleted from a mix of variable size records, where
     * each log file contains records with a different base size.  Half the
     * records in a given log have the base size, and the other half have the
     * base size * 0.9.  Base sizes range from SMALL_DATA to LARGE_DATA.
     *
     * This checks that LN size adjustment works when the average LN size is
     * very different from one log files to another.  [#21106]
     *
     * Estimated utilization is 50%, true utilization is 52.5%, so the
     * correction factor is around 1.05.
     *
     * minUtilization=80 is used to cause cleaning of all files, to ensure
     * different sized LNs are treated correctly.  An adjustment occurs for all
     * log files because they are all cleaned.
     */
    @Test
    public void testEvictDeleteSmallVariableSizesByFile() {
        minUtilization = 80;
        evictLNs = true;
        openEnv();
        expectCorrection(1.02F, 1.08F);
        writeVariableSizesByFile(false /*deleteLarger*/);
        env.cleanLog();
        final EnvironmentStats stats = env.getStats(null);
        assertTrue("" + nAdjustments, nAdjustments.get() > MIN_LOG_FILES);
        assertEquals(nAdjustments.get(), stats.getNCleanerRuns());
        assertEquals(0, stats.getNCleanerProbeRuns());
        closeEnvAndCheck();
    }

    /**
     * Only large records are deleted from a mix of large and small records.
     * Estimated utilization is 50%, true utilization is 1%, so the correction
     * factor is around 0.02.
     *
     * With minUtilization=40, probing is required to correct utilization
     * because cleaning does not occur naturally.  An adjustment occurs for all
     * log files because they are all cleaned.
     *
     * forceLogFileFlip would cause cleaning of the first file, which would be
     * 50% utilized, and the correction would not be the same as the other
     * files which only contain user DB LNs.
     */
    @Test
    public void testProbeDeleteLarge() {
        minUtilization = 40;
        evictLNs = true;
        openEnv();
        expectCorrection(0.01F, 0.03F);
        writeMixedDeleteLarge();
        env.cleanLog();
        final EnvironmentStats stats = env.getStats(null);
        assertTrue("" + nAdjustments, nAdjustments.get() > MIN_LOG_FILES);
        assertEquals(nAdjustments.get(), stats.getNCleanerRuns());
        assertEquals(1, stats.getNCleanerProbeRuns());
        closeEnvAndCheck();
    }

    /**
     * Only small records are deleted from a mix of large and small records.
     * Estimated utilization is 50%, true utilization is 99%, so the correction
     * factor is around 1.98.
     *
     * With minUtilization=40, probing is required to correct utilization
     * because cleaning does not occur naturally.  Only one adjustment occurs
     * because estimated utilization is above minUtilization after the
     * correction.
     *
     * forceLogFileFlip is called after opening the database so the initial
     * INs/MapLNs/NameLNs don't change the utilization of other files.  Without
     * flipping, in this case, the first file would have a correction of 1.94,
     * which is not the expected difference for the other files which only
     * contain user DB LNs.
     */
    @Test
    public void testProbeDeleteSmall() {
        minUtilization = 40;
        evictLNs = true;
        openEnv();
        envImpl.forceLogFileFlip();
        expectCorrection(1.97F, 1.99F);
        writeMixedDeleteSmall();
        env.cleanLog();
        final EnvironmentStats stats = env.getStats(null);
        assertEquals(1, nAdjustments.get());
        assertEquals(1, stats.getNCleanerRuns());
        assertEquals(1, stats.getNCleanerProbeRuns());
        closeEnvAndCheck();
    }

    /**
     * Same setup as testProbeDeleteSmall but because adjustment is disabled,
     * no files are cleaned and no probes occur.
     */
    @Test
    public void testDisableAjustmentDeleteSmall() {
        minUtilization = 40;
        evictLNs = true;
        final EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setConfigParam(EnvironmentConfig.CLEANER_ADJUST_UTILIZATION,
                                 "false");
        openEnv(envConfig);
        envImpl.forceLogFileFlip();
        expectCorrection(1.97F, 1.99F);
        writeMixedDeleteSmall();
        env.cleanLog();
        final EnvironmentStats stats = env.getStats(null);
        assertEquals(0, nAdjustments.get());
        assertEquals(0, stats.getNCleanerRuns());
        assertEquals(0, stats.getNCleanerProbeRuns());
        closeEnvAndCheck();
    }

    /**
     * With no deletions (or updates), we never need to perform a correction
     * run (probe).  There are no obsolete LNs, so there is no possibility of
     * miscounting and utilization is known to be accurate.
     */
    @Test
    public void testProbeDeleteNone() {
        minUtilization = 60;
        evictLNs = true;
        openEnv();
        expectCorrection(0.99F, 1.01F);
        writeMixedDeleteNone(0);
        env.cleanLog();
        assertEquals(0, nAdjustments.get());
        env.checkpoint(new CheckpointConfig().setForce(true));
        writeMixedDeleteNone(RECORD_COUNT * 2);
        env.cleanLog();
        final EnvironmentStats stats = env.getStats(null);
        assertEquals(0, nAdjustments.get());
        assertEquals(0, stats.getNCleanerRuns());
        assertEquals(0, stats.getNCleanerProbeRuns());
        closeEnvAndCheck();
        assertEquals(0, nAdjustments.get());
    }

    /**
     * Instead of the usual pattern of deleting as we go, do all insertions
     * first and then delete all records.
     *
     * forceLogFileFlip is called after opening the database so the initial
     * INs/MapLNs/NameLNs don't change the utilization of other files.  Without
     * flipping, in this case, the first file would have a correction of around
     * 0.05, which is not expected when all LNs are deleted.
     */
    @Test
    public void testProbeDeleteNoneThenAll() {
        minUtilization = 60;
        evictLNs = true;
        openEnv();
        envImpl.forceLogFileFlip();
        expectCorrection(0.99F, 1.01F);
        writeMixedDeleteNone(0);
        env.cleanLog();
        EnvironmentStats stats = env.getStats(null);
        assertEquals(0, nAdjustments.get());
        assertEquals(0, stats.getNCleanerRuns());
        assertEquals(0, stats.getNCleanerProbeRuns());
        deleteAll();
        env.cleanLog();
        stats = env.getStats(null);
        assertTrue("" + nAdjustments, nAdjustments.get() > MIN_LOG_FILES);
        assertEquals(nAdjustments.get(), stats.getNCleanerRuns());
        assertEquals(0, stats.getNCleanerProbeRuns());
        closeEnvAndCheck();
    }

    /**
     * As in testProbeDeleteSmall, only a single adjustment is needed and no
     * cleaning occurs because the true utilization is 99%.
     *
     * Here we additionally enable the cleaner thread to ensure that multiple
     * adjustments do not occur.  The first adjustment occurs after 5 files,
     * but the second would occur after 20 more files, which means the second
     * adjustment should not occur because there are only 21 files total.  This
     * tests the feature where we increase the interval from 5 to 20 after a
     * correction that is 0.9 or greater.
     */
    @Test
    public void testProbeIntervals() {
        minUtilization = 40;
        evictLNs = true;
        final EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setConfigParam
            (EnvironmentParams.CLEANER_CALC_INITIAL_ADJUSTMENTS.getName(),
             "1");
        openEnv(envConfig);
        envImpl.forceLogFileFlip();
        enableCleanerThread();
        expectCorrection(1.94F, 1.99F);
        writeMixedDeleteSmall();
        env.cleanLog();
        final EnvironmentStats stats = env.getStats(null);
        assertEquals(1, nAdjustments.get());
        /* Checkpointer/cleaner threads may cause more than one cleaner run. */
        assertTrue(stats.getNCleanerRuns() > 0);
        /* But only one cleaner run should be a probe (two in odd cases). */
        final long nProbes = stats.getNCleanerProbeRuns();
        assertTrue(String.valueOf(nProbes), nProbes >= 1 && nProbes <= 2);
        closeEnvAndCheck();
    }

    /**
     * Force several probes to ensure same file is not probed twice in a row.
     * This is checked by expectCorrection.  Correction factor is irrelevant.
     * [#22814]
     */
    @Test
    public void testRepeatedProbe() {
        minUtilization = 20;
        evictLNs = true;
        final EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setConfigParam(
            EnvironmentParams.CLEANER_CALC_MIN_PROBE_SKIP_FILES.getName(),
            "1");
        envConfig.setConfigParam(
            EnvironmentParams.CLEANER_CALC_MAX_PROBE_SKIP_FILES.getName(),
            "1");
        openEnv(envConfig);
        expectCorrection(0.01F, 10.00F);
        writeMixedDeleteSmall();
        env.cleanLog();
        writeMixedDeleteNone(RECORD_COUNT * 2);
        env.cleanLog();
        writeMixedDeleteNone(RECORD_COUNT * 4);
        env.cleanLog();
        EnvironmentStats stats = env.getStats(null);
        assertEquals(3, nAdjustments.get());
        assertEquals(3, stats.getNCleanerRuns());
        assertEquals(3, stats.getNCleanerProbeRuns());
        closeEnvAndCheck();
    }

    /**
     * Perform simple cleaning (copy of testInCacheDeleteAll) but fiddle with
     * the estimated (UtilizationProfile) FileSummaries to simulate double
     * counting during recovery.  Except that no adjustments will occur,
     * because of fixes to reject invalid corrections.  [#22814]
     */
    @Test
    public void testRejectCorrection() {
        minUtilization = 80;
        evictLNs = true;
        openEnv();
        writeMixedDeleteLarge();
        simulateDoubleCounting();
        env.cleanLog();
        final EnvironmentStats stats = env.getStats(null);
        assertTrue("" + stats.getNCleanerRuns(),
                   stats.getNCleanerRuns() > MIN_LOG_FILES);
        assertEquals(Float.NaN, stats.getLNSizeCorrectionFactor(), 0);
        assertEquals(0, stats.getNCleanerProbeRuns());
        closeEnvAndCheck();
    }

    /**
     * When recovery can't count obsolete LNs accurately, the obsoleteLNCount
     * will be incorrect.  In that case a size correction should be rejected.
     * Simulate that here.
     */
    private void simulateDoubleCounting() {
        /* Flush outstanding changes to the utilization map. */
        envImpl.getUtilizationProfile().flushFileUtilization(
            envImpl.getUtilizationTracker().getTrackedFiles());
        /* Fiddle with the map. */
        for (final FileSummary summary :
             envImpl.getUtilizationProfile().getMapForTesting().values()) {
            summary.obsoleteLNCount += 10;
        }
    }

    /**
     * Set a hook that is called when utilization is adjusted, which occurs at
     * the end of a regular FileProcessor run to clean a file, or when a
     * special FileProcessor run is made to probe true utilization.
     */
    private void expectCorrection(final float min, final float max) {
        calculator.setAdjustmentHook(new TestHook<TestAdjustment>() {
            public void doHook(TestAdjustment adjustment) {

                /* Record that this method was called. */
                nAdjustments.incrementAndGet();

                /* Sanity checks. */
                final FileSummary trueSummary = adjustment.trueFileSummary;
                final FileSummary estSummary = adjustment.estimatedFileSummary;
                assertEquals(trueSummary.totalCount,
                             estSummary.totalCount);
                assertEquals(trueSummary.totalSize,
                             estSummary.totalSize);
                assertEquals(trueSummary.totalINCount,
                             estSummary.totalINCount);
                assertEquals(trueSummary.totalINSize,
                             estSummary.totalINSize);
                assertEquals(trueSummary.totalLNCount,
                             estSummary.totalLNCount);
                assertEquals(trueSummary.totalLNSize,
                             estSummary.totalLNSize);
                assertEquals(trueSummary.obsoleteINCount,
                             estSummary.obsoleteINCount);
                assertEquals(trueSummary.obsoleteLNCount,
                             estSummary.obsoleteLNCount);
                assertEquals(trueSummary.obsoleteLNSizeCounted,
                             trueSummary.obsoleteLNCount);
                if (!evictLNs) {
                    assertEquals(estSummary.obsoleteLNSizeCounted,
                                 estSummary.obsoleteLNCount);
                }

                if (DEBUG) {
                    System.out.println("min=" + min + " max=" + max +" " +
                                       adjustmentToString(adjustment));
                }

                /* Calculate utilization correction. */
                final float estimatedUtil = FileSummary.utilization
                    (estSummary.getObsoleteSize(), estSummary.totalSize);
                final float trueUtil = FileSummary.utilization
                    (trueSummary.getObsoleteSize(), trueSummary.totalSize);
                final float utilCorrection;
                if (estimatedUtil == 0) {
                    /* Don't divide by zero. */
                    if (trueUtil <= 1.0F) {
                        utilCorrection = 1.0F;
                    } else {
                        utilCorrection = trueUtil;
                    }
                } else {
                    utilCorrection = trueUtil / estimatedUtil;
                }

                final String msg = "utilCorrection=" + utilCorrection +
                                   " min=" + min + " max=" + max +
                                   " estimatedUtil=" + estimatedUtil +
                                   " trueUtil=" + trueUtil +
                                   " " + adjustmentToString(adjustment);

                /* Check that utilization correction is within min/max. */
                assertTrue(msg,
                           utilCorrection <= max && utilCorrection >= min);

                /*
                 * Check that if that true average size is used to correct the
                 * original estimated utilization, we get the true utilization.
                 */
                final float adjustedUtil = FileSummary.utilization
                    (estSummary.getObsoleteSize(adjustment.correctionFactor),
                     estSummary.totalSize);
                assertEquals(msg, trueUtil, adjustedUtil, DELTA);

                /* Check stats. */
                final EnvironmentStats stats = env.getStats(null);
                if (evictLNs) {
                    final float expectedCorrection = (min + max) / 2;
                    if (expectedCorrection == 1.0F) {
                        assertEquals(1.0F, stats.getLNSizeCorrectionFactor(), 
                                     DELTA);
                    } else if (expectedCorrection > 1.0F) {
                        assertTrue(msg,
                                   stats.getLNSizeCorrectionFactor() < 1.0F);
                    } else {
                        assertTrue(expectedCorrection < 1.0F);
                        assertTrue(msg,
                                   stats.getLNSizeCorrectionFactor() > 1.0F);
                    }
                } else {
                    assertEquals(msg, Float.NaN,
                                 stats.getLNSizeCorrectionFactor(), DELTA);
                }

                /* Ensure same file is not probed repeatedly. */
                assertTrue(lastCleanedFile != adjustment.fileNum);
                lastCleanedFile = adjustment.fileNum;
            }
            public void doHook() {
                throw new UnsupportedOperationException();
            }
            public TestAdjustment getHookValue() {
                throw new UnsupportedOperationException();
            }
            public void doIOHook() {
                throw new UnsupportedOperationException();
            }
            public void hookSetup() {
                throw new UnsupportedOperationException();
            }
        });
    }

    private String adjustmentToString(TestAdjustment adjustment) {
        return "file=" + adjustment.fileNum +
               " endFile=" + adjustment.endFileNum +
               " estimatedAvgSize=" + adjustment.estimatedAvgSize +
               " trueAvgSize=" + adjustment.trueAvgSize +
               " correctionFactor=" + adjustment.correctionFactor +
               "\n estimated=" + adjustment.estimatedFileSummary +
               "\n true=" + adjustment.trueFileSummary;
    }

    private void writeLargeDeleteAll() {
        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        for (int i = 0; i < RECORD_COUNT; i += 1) {
            /* Large */
            IntegerBinding.intToEntry(i, key);
            data.setData(new byte[LARGE_DATA]);
            OperationStatus status = db.putNoOverwrite(null, key, data);
            assertSame(OperationStatus.SUCCESS, status);
            status = db.delete(null, key);
            assertSame(OperationStatus.SUCCESS, status);
        }
        env.compress();
    }

    private void writeMixedDeleteLarge() {
        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        for (int i = 0; i < RECORD_COUNT; i += 1) {
            /* Small */
            IntegerBinding.intToEntry(i, key);
            data.setData(new byte[SMALL_DATA]);
            OperationStatus status = db.putNoOverwrite(null, key, data);
            assertSame(OperationStatus.SUCCESS, status);
            /* Large */
            IntegerBinding.intToEntry(RECORD_COUNT + i, key);
            data.setData(new byte[LARGE_DATA]);
            status = db.putNoOverwrite(null, key, data);
            assertSame(OperationStatus.SUCCESS, status);
            status = db.delete(null, key);
            assertSame(OperationStatus.SUCCESS, status);
        }
        env.compress();
    }

    private void writeMixedDeleteSmall() {
        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        for (int i = 0; i < RECORD_COUNT; i += 1) {
            /* Small */
            IntegerBinding.intToEntry(i, key);
            data.setData(new byte[SMALL_DATA]);
            OperationStatus status = db.putNoOverwrite(null, key, data);
            assertSame(OperationStatus.SUCCESS, status);
            status = db.delete(null, key);
            assertSame(OperationStatus.SUCCESS, status);
            /* Large */
            IntegerBinding.intToEntry(RECORD_COUNT + i, key);
            data.setData(new byte[LARGE_DATA]);
            status = db.putNoOverwrite(null, key, data);
            assertSame(OperationStatus.SUCCESS, status);
        }
        env.compress();
    }

    private void writeMixedDeleteNone(int startRecord) {
        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        for (int i = startRecord; i < startRecord + RECORD_COUNT; i += 1) {
            /* Small */
            IntegerBinding.intToEntry(i, key);
            data.setData(new byte[SMALL_DATA]);
            OperationStatus status = db.putNoOverwrite(null, key, data);
            assertSame(OperationStatus.SUCCESS, status);
            /* Large */
            IntegerBinding.intToEntry(startRecord + RECORD_COUNT + i, key);
            data.setData(new byte[LARGE_DATA]);
            status = db.putNoOverwrite(null, key, data);
            assertSame(OperationStatus.SUCCESS, status);
        }
        env.compress();
    }

    private void deleteAll() {
        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        data.setPartial(0, 0, true);
        final Cursor cursor = db.openCursor(null, null);
        while (cursor.getNext(key, data, null) == OperationStatus.SUCCESS) {
            OperationStatus status = cursor.delete();
            assertSame(OperationStatus.SUCCESS, status);
        }
        cursor.close();
        env.compress();
    }

    private void writeVariableSizesByFile(boolean deleteLarger) {
        final float sizeFactor = deleteLarger ? 1.1F : 0.9F;
        final int incr = ((LARGE_DATA - SMALL_DATA) / MIN_LOG_FILES);
        int keyVal = 0;
        for (int size = SMALL_DATA; size <= LARGE_DATA; size += incr) {
            keyVal = writeVariableSizeSingleFile(keyVal, size, sizeFactor);
        }
        for (int size = LARGE_DATA; size >= SMALL_DATA; size -= incr) {
            keyVal = writeVariableSizeSingleFile(keyVal, size, sizeFactor);
        }
        env.compress();
    }

    private int writeVariableSizeSingleFile(int keyVal,
                                            int insertSize,
                                            float deleteSizeFactor) {
        final int deleteSize = (int) (insertSize * deleteSizeFactor);
        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        final long file = envImpl.getFileManager().getCurrentFileNum();
        while (file == envImpl.getFileManager().getCurrentFileNum()) {
            keyVal += 1;
            /* Insert with insertSize. */
            IntegerBinding.intToEntry(keyVal, key);
            data.setData(new byte[insertSize]);
            OperationStatus status = db.putNoOverwrite(null, key, data);
            assertSame(OperationStatus.SUCCESS, status);
            /* Insert with deleteSize and delete. */
            IntegerBinding.intToEntry(-keyVal, key);
            data.setData(new byte[deleteSize]);
            status = db.putNoOverwrite(null, key, data);
            assertSame(OperationStatus.SUCCESS, status);
            status = db.delete(null, key);
            assertSame(OperationStatus.SUCCESS, status);
        }
        return keyVal;
    }

    private void enableCleanerThread() {
        EnvironmentMutableConfig config = env.getMutableConfig();
        config.setConfigParam(EnvironmentConfig.ENV_RUN_CLEANER, "true");
        env.setMutableConfig(config);
    }

    /** For debugging.  */
    private void printDbSpace() {
        final DbSpace space = new DbSpace
            (env, false /*quiet*/, true /*details*/, false /*sorted*/);
        space.print(System.out);
    }

    /** For debugging.  */
    private void readAll() {
        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry data = new DatabaseEntry();
        final Cursor cursor = db.openCursor(null, null);
        while (cursor.getNext(key, data, null) == OperationStatus.SUCCESS) {
        }
        cursor.close();
    }

    /** For debugging.  */
    private void flushAndCloseAbnormal() {
        envImpl.getUtilizationProfile().flushFileUtilization
            (envImpl.getUtilizationTracker().getTrackedFiles());
        envImpl.forceLogFileFlip();
        openEnv();
        printDbSpace();
        envImpl.abnormalClose();
        openEnv();
        printDbSpace();
    }
}
