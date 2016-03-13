/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.vlsn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.junit.Test;

import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Durability;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.je.dbi.DatabaseImpl;
import com.sleepycat.je.log.LogEntryType;
import com.sleepycat.je.recovery.RecoveryInfo;
import com.sleepycat.je.rep.impl.node.NameIdPair;
import com.sleepycat.je.rep.util.TestLogItem;
import com.sleepycat.je.rep.vlsn.VLSNIndex.BackwardVLSNScanner;
import com.sleepycat.je.rep.vlsn.VLSNIndex.ForwardVLSNScanner;
import com.sleepycat.je.txn.Txn;
import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.je.utilint.TestHook;
import com.sleepycat.je.utilint.VLSN;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/**
 * Exercise VLSNIndex
 */
public class VLSNIndexTest extends TestBase {

    private final String testMapDb = "TEST_MAP_DB";
    private final boolean verbose = Boolean.getBoolean("verbose");
    private final File envRoot;

    public VLSNIndexTest() {
        envRoot = SharedTestUtils.getTestDir();
    }

    private Environment makeEnvironment()
        throws DatabaseException {

        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(false);
        return new Environment(envRoot, envConfig);
    }

    @Test
    public void testNonFlushedGets()
        throws Throwable {

        doGets(false); // flush
    }

    @Test
    public void testFlushedGets()
        throws Throwable {

        doGets(true); // flush
    }

    // TODO: test decrementing the vlsn

    /**
     * Populate a vlsnIndex, and retrieve mappings.
     * @param flush if true, write the vlsn index to disk, so that the
     * subsequent get() calls fetch the mappings off disk.
     */
    private void doGets(boolean flush)
        throws Throwable {

        int stride = 3;
        int maxMappings = 4;
        int maxDist = 1000;

        Environment env = makeEnvironment();
        VLSNIndex vlsnIndex = null;

        try {
            vlsnIndex = new VLSNIndex(DbInternal.getEnvironmentImpl(env),
                                      testMapDb, new NameIdPair("n1",1),
                                      stride, maxMappings, maxDist,
                                      new RecoveryInfo());

            int numEntries = 25;

            /*
             * Put some mappings in. With the strides, we expect them to
             * end up in
             * Bucket 1 = vlsn 1, 4, 7, 10, 12
             * Bucket 2 = vlsn 13, 16, 19, 22, 24
             * Bucket 3 = vlsn 25
             */
            for (int i = 1; i <= numEntries; i++) {
                putEntryToVLSNIndex(i, 33, 100, vlsnIndex);
            }

            /* We expect these mappings. */
            TreeMap<VLSN, Long> expected = new TreeMap<VLSN, Long>();
            long[] expectedVLSN = { 
                1, 4, 7, 10, 12, 13, 
                16, 19, 22, 24, 25 
            };
            makeExpectedMapping(expected, expectedVLSN, 33, 100);

            if (flush) {
                vlsnIndex.flushToDatabase(Durability.COMMIT_NO_SYNC);
            }

            VLSNRange range = vlsnIndex.getRange();
            assertEquals(expected.firstKey(), range.getFirst());
            assertEquals(expected.lastKey(), range.getLast());

            ForwardVLSNScanner fScanner = new ForwardVLSNScanner(vlsnIndex);
            Long startLsn = fScanner.getStartingLsn(expected.firstKey());
            assertEquals(expected.get(expected.firstKey()), startLsn);

            for (int i = 1; i <= numEntries; i++) {
                VLSN vlsn = new VLSN(i);
                Long expectedLsn = expected.get(vlsn);
                Long scannerLsn = fScanner.getPreciseLsn(vlsn);

                if (expectedLsn == null) {
                    assertEquals((Long)DbLsn.NULL_LSN, scannerLsn);

                    /* 
                     * If there's no exact match, approximate search should
                     * return the one just previous.
                     */
                    Long prevLsn = null;
                    for (int find = i - 1; find >= 0; find--) {
                        prevLsn = expected.get(new VLSN(find));
                        if (prevLsn != null)
                            break;
                    }
                    assertEquals(prevLsn, 
                                 (Long) fScanner.getApproximateLsn(vlsn));
                } else {
                    assertEquals(expectedLsn, scannerLsn);
                    assertEquals(expectedLsn, 
                                 (Long) fScanner.getApproximateLsn(vlsn));
                }
            }

            BackwardVLSNScanner bScanner=  new BackwardVLSNScanner(vlsnIndex);
            startLsn = bScanner.getStartingLsn(expected.lastKey());
            assertEquals(expected.get(expected.lastKey()), startLsn);

            for (int i = numEntries; i >= 1; i--) {
                VLSN vlsn = new VLSN(i);
                Long expectedLsn = expected.get(vlsn);
                Long scannerLsn = bScanner.getPreciseLsn(vlsn);

                if (expectedLsn == null) {
                    assertEquals((Long)DbLsn.NULL_LSN, scannerLsn);
                } else {
                    assertEquals(expectedLsn, scannerLsn);
                }
            }

            /*
             * Check that we get the less than or equal mapping when we
             * ask to start at a given VLSN.
             */
            ForwardVLSNScanner forwards = new ForwardVLSNScanner(vlsnIndex);
            BackwardVLSNScanner backwards = new BackwardVLSNScanner(vlsnIndex);
            checkStartLsn(forwards, backwards, 1,
                          DbLsn.makeLsn(33, 100),
                          DbLsn.makeLsn(33, 100));
            checkStartLsn(forwards, backwards, 2,
                          DbLsn.makeLsn(33, 100),
                          DbLsn.makeLsn(33, 400));
            checkStartLsn(forwards, backwards, 3,
                          DbLsn.makeLsn(33, 100),
                          DbLsn.makeLsn(33, 400));
            checkStartLsn(forwards, backwards, 4,
                          DbLsn.makeLsn(33, 400),
                          DbLsn.makeLsn(33, 400));
            checkStartLsn(forwards, backwards, 5,
                          DbLsn.makeLsn(33, 400),
                          DbLsn.makeLsn(33, 700));
            checkStartLsn(forwards, backwards, 6,
                          DbLsn.makeLsn(33, 400),
                          DbLsn.makeLsn(33, 700));
            checkStartLsn(forwards, backwards, 7,
                          DbLsn.makeLsn(33, 700),
                          DbLsn.makeLsn(33, 700));
            checkStartLsn(forwards, backwards, 8,
                          DbLsn.makeLsn(33, 700),
                          DbLsn.makeLsn(33, 1000));
            checkStartLsn(forwards, backwards, 9,
                          DbLsn.makeLsn(33, 700),
                          DbLsn.makeLsn(33, 1000));
            checkStartLsn(forwards, backwards, 10,
                          DbLsn.makeLsn(33, 1000),
                          DbLsn.makeLsn(33, 1000));
            checkStartLsn(forwards, backwards, 11,
                          DbLsn.makeLsn(33, 1000),
                          DbLsn.makeLsn(33, 1200));
            checkStartLsn(forwards, backwards, 12,
                          DbLsn.makeLsn(33, 1200),
                          DbLsn.makeLsn(33, 1200));
            checkStartLsn(forwards, backwards, 13,
                          DbLsn.makeLsn(33, 1300),
                          DbLsn.makeLsn(33, 1300));
            checkStartLsn(forwards, backwards, 14,
                          DbLsn.makeLsn(33, 1300),
                          DbLsn.makeLsn(33, 1600));
            checkStartLsn(forwards, backwards, 15,
                          DbLsn.makeLsn(33, 1300),
                          DbLsn.makeLsn(33, 1600));
            checkStartLsn(forwards, backwards, 16,
                          DbLsn.makeLsn(33, 1600),
                          DbLsn.makeLsn(33, 1600));
            checkStartLsn(forwards, backwards, 17,
                          DbLsn.makeLsn(33, 1600),
                          DbLsn.makeLsn(33, 1900));
            checkStartLsn(forwards, backwards, 18,
                          DbLsn.makeLsn(33, 1600),
                          DbLsn.makeLsn(33, 1900));
            checkStartLsn(forwards, backwards, 19,
                          DbLsn.makeLsn(33, 1900),
                          DbLsn.makeLsn(33, 1900));
            checkStartLsn(forwards, backwards, 20,
                          DbLsn.makeLsn(33, 1900),
                          DbLsn.makeLsn(33, 2200));
            checkStartLsn(forwards, backwards, 21,
                          DbLsn.makeLsn(33, 1900),
                          DbLsn.makeLsn(33, 2200));
            checkStartLsn(forwards, backwards, 22,
                          DbLsn.makeLsn(33, 2200),
                          DbLsn.makeLsn(33, 2200));
            checkStartLsn(forwards, backwards, 23,
                          DbLsn.makeLsn(33, 2200),
                          DbLsn.makeLsn(33, 2400));
            checkStartLsn(forwards, backwards, 24,
                          DbLsn.makeLsn(33, 2400),
                          DbLsn.makeLsn(33, 2400));
            checkStartLsn(forwards, backwards, 25,
                          DbLsn.makeLsn(33, 2500),
                          DbLsn.makeLsn(33, 2500));
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        } finally {
            if (vlsnIndex != null) {
                vlsnIndex.close();
            }

            env.removeDatabase(null, testMapDb);
            env.close();
        }
    }

    private void checkStartLsn(ForwardVLSNScanner forwardScanner,
                               BackwardVLSNScanner backwardScanner,
                               int targetVLSNVal,
                               long expectedForwardStart,
                               long expectedBackwardStart)
        throws DatabaseException {

        VLSN target = new VLSN(targetVLSNVal);
        long startLsn = forwardScanner.getStartingLsn(target);
        long endLsn = backwardScanner.getStartingLsn(target);

        assertEquals("target=" +
                     DbLsn.getNoFormatString(expectedForwardStart) +
                     " got = " + DbLsn.getNoFormatString(startLsn),
                     expectedForwardStart, startLsn);

        assertEquals("target=" +
                     DbLsn.getNoFormatString(expectedBackwardStart) +
                     " got = " + DbLsn.getNoFormatString(endLsn),
                     expectedBackwardStart, endLsn);
    }

    /*
     * VLSN puts are done out of the log write latch, and can therefore show
     * up out of order.
     */
    @Test
    public void testOutOfOrderPuts()
        throws Throwable {

        int stride = 3;
        int maxMappings = 4;
        int maxDist = 1000;

        Environment env = makeEnvironment();
        byte lnType = LogEntryType.LOG_INS_LN_TRANSACTIONAL.getTypeNum();
        byte commitType = LogEntryType.LOG_TXN_COMMIT.getTypeNum();
        byte syncType = LogEntryType.LOG_MATCHPOINT.getTypeNum();

        Mapping[] mappings = new Mapping[] {new Mapping(1, 1, 0, lnType),
                                            new Mapping(2, 2, 100, commitType),
                                            new Mapping(3, 2, 200, lnType),
                                            new Mapping(4, 3, 100, commitType),
                                            new Mapping(5, 3, 200, lnType),
                                            new Mapping(6, 4, 100, lnType),
                                            new Mapping(7, 4, 200, syncType),
                                            new Mapping(8, 4, 300, lnType),
                                            new Mapping(9, 5, 100, lnType)};

        Long[] loadOrder = new Long [] {1L, 2L, 5L, 3L, 6L, 4L, 8L, 9L, 7L};

        try {
            for (int flushIndex = -1;
                 flushIndex < mappings.length;
                 flushIndex++ ) {

                MappingLoader loader = null;
                try {
                    loader = new MappingLoader(env,
                                               stride,
                                               maxMappings,
                                               maxDist,
                                               mappings,
                                               loadOrder,
                                               4, // minimum mappings
                                               flushIndex);
                    loader.verify(new VLSN(7),  // lastSync
                                  new VLSN(4)); // lastTxnEnd

                } catch (Throwable t) {
                    t.printStackTrace();
                    throw t;
                } finally {
                    if (loader != null) {
                        loader.close();
                    }

                    env.removeDatabase(null, testMapDb);
                }
            }
        } finally {
            env.close();
        }
    }

    private class MappingLoader {
        final private int minimumMappings;
        final TreeMap<Long, Mapping> expected = new TreeMap<Long, Mapping>();
        final VLSNIndex vlsnIndex;
        final private Long firstInRange;

        MappingLoader(Environment env,
                      int stride,
                      int maxMappings,
                      int maxDist,
                      Mapping[] mappings,
                      Long[] loadOrder,
                      int minimumMappings,
                      int flushIndex)
            throws DatabaseException {

            this.minimumMappings = minimumMappings;

            vlsnIndex = new VLSNIndex(DbInternal.getEnvironmentImpl(env),
                                      testMapDb, new NameIdPair("n1", 1),
                                      stride, maxMappings, maxDist,
                                      new RecoveryInfo());

            /* initialize the expected map. */
            for (Mapping m : mappings) {
                expected.put(m.vlsn.getSequence(), m);
            }

            /* Load the vlsnIndex. */
            for (int i = 0; i < loadOrder.length; i++) {
                long vlsnVal = loadOrder[i];
                Mapping m = expected.get(vlsnVal);
                if (verbose) {
                    System.out.println("put " + m);
                }
                vlsnIndex.put(new TestLogItem(new VLSN(vlsnVal), m.lsn,
                                              m.entryTypeNum));

                if (i == flushIndex) {
                    vlsnIndex.flushToDatabase(Durability.COMMIT_NO_SYNC);
                }
            }
            firstInRange = mappings[0].vlsn.getSequence();

            if (verbose) {
                System.out.println("flush at " + flushIndex);
            }

        }

        void verify(VLSN lastSyncVLSN, VLSN lastTxnEnd)
            throws DatabaseException {

            VLSNRange range = vlsnIndex.getRange();
            assert(firstInRange == range.getFirst().getSequence()) :
            "first=" + firstInRange + " range=" + range;
            assert(expected.lastKey() == range.getLast().getSequence()) :
            "last=" + expected.lastKey() + " range=" + range;
            assert vlsnIndex.verify(verbose);

            assertEquals(lastSyncVLSN, range.getLastSync());
            assertEquals(lastTxnEnd, range.getLastTxnEnd());

            /*
             * Check that the mappings, both vlsn and lsn value, are what
             * we expect. Scan forwards.
             *
             * This test assumes that the first vlsn may not be mapped. In
             * reality, vlsn1 is always create first, thereby guaranteeing that
             * the vlsn index always has a starting range point. Log file
             * cleaning and head truncation maintain that by creating the ghost
             * bucket. But in this test case, there isn't a starting mapping,
             * so we call scanner.getStartingLsn from the first in the range.
             */
            int numMappings = 0;
            ForwardVLSNScanner fScanner = new ForwardVLSNScanner(vlsnIndex);
            assertEquals(new Long(expected.get(firstInRange).lsn),
                         new Long(fScanner.getStartingLsn
                                 (new VLSN(firstInRange))));

            for (Map.Entry<Long, Mapping> e : expected.entrySet()) {
                Long vlsnValue = e.getKey();
                if (vlsnValue < firstInRange) {
                    continue;
                }
                VLSN vlsn = new VLSN(vlsnValue);
                long scannedLsn = fScanner.getPreciseLsn(vlsn);
                if (scannedLsn != DbLsn.NULL_LSN) {
                    numMappings++;
                    assert(e.getValue().lsn == scannedLsn);
                }
            }

            assert numMappings >= minimumMappings : "numMappings = " +
                numMappings;

            /* Scan backwards. */
            numMappings = 0;
            BackwardVLSNScanner bScanner = new BackwardVLSNScanner(vlsnIndex);
            Long lastKey = expected.lastKey();
            assertEquals(expected.get(lastKey).lsn,
                         bScanner.getStartingLsn(new VLSN(lastKey)));

            SortedMap<Long, Mapping> reverse = reverseExpected(expected);
            for (Map.Entry<Long, Mapping> e : reverse.entrySet()) {
                Long vlsnValue = e.getKey();
                if (vlsnValue < firstInRange) {
                    break;
                }
                VLSN vlsn = new VLSN(vlsnValue);
                long scannedLsn = bScanner.getPreciseLsn(vlsn);
                if (scannedLsn != DbLsn.NULL_LSN) {
                    numMappings++;
                    assert(e.getValue().lsn == scannedLsn);
                }
            }

            assert numMappings >= minimumMappings : "numMappings = " +
                numMappings;
        }

        void close()
            throws DatabaseException {
            if (vlsnIndex != null) {
                vlsnIndex.close();
            }
        }
    }

    private SortedMap<Long, Mapping>
        reverseExpected(SortedMap<Long, Mapping> expected) {

        SortedMap<Long, Mapping> r = new TreeMap<Long, Mapping> (new Reverse());
        r.putAll(expected);
        return r;
    }

    private static class Reverse implements Comparator<Long> {
        public int compare(Long a, Long b) {
            return (int) (b - a);
        }
    }

    private static class Mapping {
        final VLSN vlsn;
        final long lsn;
        final byte entryTypeNum;

        Mapping(long vlsnVal,
                long fileNumber,
                long offset,
                byte entryTypeNum) {
            this.vlsn = new VLSN(vlsnVal);
            this.lsn = DbLsn.makeLsn(fileNumber, offset);
            this.entryTypeNum = entryTypeNum;
        }

        @Override
            public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("vlsn=" + vlsn + " lsn=" + DbLsn.getNoFormatString(lsn) +
                      " type=" + entryTypeNum);
            return sb.toString();
        }
    }
    
    /*
     * [SR#17765] Create a VLSNBucket with holes in it, then check that if 
     * VLSNScanners can work forwards and backwards. 
     *
     * There are three test cases: 
     *   1. Small holes between two neighbor buckets (gap = 1); 
     *   2. Large holes between two neighbor buckets (gap > 1); 
     *   3. A ghostBucket is inserted at the beginning of the VLSN range.
     *
     * For the first 2 test cases, we use the test logic like this:
     *   1. Manually create holes between the buckets;
     *   2. Use forwardVLSNScanner and backwardVLSNScanner to travel through 
     *      the buckets;
     *   3. In forward scanning, if the missing VLSN is visited, the previous 
     *      LSN will be returned;
     *   4. In backward scanning, if the missing VLSN is visited, the LSN_NULL 
     *      will be returned;
     *   5. Make sure no exception will be thrown during the scanning. Any 
     *      exception means there's an issue in the VLSNBuckt code.
     *
     * This function tests the first case as mentioned above.
     *
     */
    @Test
    public void testNonContiguousBucketSmallHoles() 
        throws Throwable {
        
        /* The JE database log file (.jdb file) number. */
        int fileNum = 33;      
        /* The offset between two contiguous VLSN in log file. */
        int offset = 100;      
        /* The stride between two neighbor VLSN sequence in one bucket. */
        int stride = 3;    
        /* The max number of VLSN->LSN mappings saved in one bucket. */
        int maxMappings = 4;   
        /* The max distance between two neighbor VLSNs in one bucket. */        
        int maxDist = 1000;   
        /* The number of VLSN entries specified in one bucket. */        
        int numEntries = 30;
        
        /*
         * We create small holes in the buckets, each hole misses one VLSN (in
         * this case, the missing VLSNs are 12 and 24). 
         *
         * With the strides and holes, we expect the buckets to end up like:
         *   Bucket 1 = { vlsn = 1, 4, 7, 10, 11 }
         *   Bucket 2 = { vlsn = 13, 16, 19, 22, 23 }
         *   Bucket 3 = { vlsn = 25, 28, 30 }
         */   
        long[] holes = { 12, 24 };
        /* We will use expectedVLSN to generate the expected mappings.*/
        long[] expectedVLSN = { 
            1, 4, 7, 10, 11, 13, 16, 
            19, 22, 23, 25, 28, 30 
        };
        
        /* Now traverse through (back and forth) buckets with small holes. */
        scanNonContiguousBucketWithHoles(fileNum, offset, stride, maxMappings, 
                                         maxDist, numEntries, holes, 
                                         expectedVLSN, false  /* if flush */);
    }
    
    /*
     * [SR#17765] Create a VLSNBucket with holes in it, then check that if
     * VLSNScanners can work forwards and backwards.
     *
     * There are three test cases:
     *   1. Small holes between two neighbor buckets (gap = 1);
     *   2. Large holes between two neighbor buckets (gap > 1);
     *   3. A ghostBucket is inserted at the beginning of the VLSN range.
     *
     * For the first 2 test cases, we use the test logic like this:
     *   1. Manually create holes between the buckets;
     *   2. Use forwardVLSNScanner and backwardVLSNScanner to travel through 
     *      the buckets;
     *   3. In forward scanning, if the missing VLSN is visited, the previous 
     *      LSN will be returned;
     *   4. In backward scanning, if the missing VLSN is visited, the LSN_NULL 
     *      will be returned;
     *   5. Make sure no exception will be thrown during the scanning. Any 
     *      exception means there's an issue in the VLSNBuckt code.
     *
     * This function tests the second case as mentioned above.
     *
     */     
    @Test
    public void testNonContiguousBucketLargeHoles() 
        throws Throwable {
        
        /* The JE database log file (.jdb file) number. */
        long fileNum = 33;      
        /* The offset between two contiguous VLSN in log file. */
        long offset = 100;      
        /* The stride between two neighbor VLSN sequence in one bucket. */
        int stride = 5;    
        /* The max number of VLSN->LSN mappings saved in one bucket. */
        int maxMappings = 4;   
        /* The max distance between two neighbor VLSNs in one bucket. */        
        int maxDist = 1000;   
        /* The number of VLSN entries specified in one bucket. */        
        int numEntries = 50;
        
        /*
         * We create large holes in the buckets, each hole misses three VLSN 
         * (in this case, the missing VLSNs are 18,19,20, and 38,39,40). 
         * 
         * With the strides and holes, we expect the buckets to end up like:
         *   Bucket 1 = { vlsn = 1, 6, 11, 16, 17 }
         *   Bucket 2 = { vlsn = 21, 26, 31, 36, 37 }
         *   Bucket 3 = { vlsn = 41, 46, 50 }
         */   
        long[] holes = { 18, 19, 20, 38, 39, 40 };
        /* We will use expectedVLSN to generate the expected mappings. */
        long[] expectedVLSN = { 
            1, 6, 11, 16, 17, 21, 26, 
            31, 36, 37, 41, 46, 50 
        };
        
        /* Now traverse through (back and forth) buckets with small holes. */
        scanNonContiguousBucketWithHoles(fileNum, offset, stride, maxMappings, 
                                         maxDist, numEntries, holes, 
                                         expectedVLSN, false  /* if flush */);       
    }
    
    /* 
     * The buckets with given holes are created, then use VLSNScanner to scan 
     * the buckets forwards and backwards.
     */
    private void scanNonContiguousBucketWithHoles(long fileNum,
                                                  long offset,
                                                  int stride,
                                                  int maxMappings,
                                                  int maxDist,
                                                  int numEntries,
                                                  long[] holesInAscOrder,
                                                  long[] expectedVLSN,
                                                  boolean flush)
        throws Throwable {
       
        /* Use a standalone env to simplify the test - no rep env required. */
        Environment env = makeEnvironment();
        /* The vlsnIndex is used to put and read VLSNs. */
        VLSNIndex vlsnIndex = null;
        try {
        
            /* 
             * Create a vlsnIndex with the given stride, maxMappings and 
             * maxDist, under the given environment and mapping database.
             */
            vlsnIndex = new VLSNIndex(DbInternal.getEnvironmentImpl(env),
                                      testMapDb, new NameIdPair("n1",1),
                                      stride, maxMappings, maxDist,
                                      new RecoveryInfo());  
            
            /*
             * Put some mappings in the buckets through vlsnIndex. We create 
             * holes in the buckets, according to the given holes parameter.
             */            
            for (int i = 1; i <= numEntries; i++) {
            
                /*
                 * Since holes[] is already in sorted order, we can use
                 * Arrays.binarySearch to check if an item is in holes[].
                 */
                if (java.util.Arrays.binarySearch(holesInAscOrder, i) < 0) {
                    /* If not exist, insert it into the VLSNIndex. */
                    putEntryToVLSNIndex(i, fileNum, offset, vlsnIndex);
                }
            }
            if (flush) {
                vlsnIndex.flushToDatabase(Durability.COMMIT_NO_SYNC);
            }
                       
            TreeMap<VLSN, Long> expected = new TreeMap<VLSN, Long>();
            
            /* 
             * We expect these mappings. These expected mappings assist in 
             * checking the correctness of the scanning process.
             */
            makeExpectedMapping(expected, expectedVLSN, fileNum, offset);
            VLSNRange range = vlsnIndex.getRange();
            assertEquals(expected.firstKey(), range.getFirst());
            assertEquals(expected.lastKey(), range.getLast());
            
            /* 
             * Scanning the VLSN buckets forwards and backwards. The starting 
             * point is from every VLSN.
             */
            for(int i = 1; i <= numEntries; i++) {
                forwardScanning(vlsnIndex, numEntries, expected, i);
                backwardScanning(vlsnIndex, expected, i);
            }    
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        } finally {
            if (vlsnIndex != null) {
                vlsnIndex.close();
            }
            env.removeDatabase(null, testMapDb);
            env.close();
        }
    }
   
    /* 
     * [#17765] In the third case, a ghostBucket is created and inserted into
     * the beginning of the VLSN range, i.e., the ghostBucket is the first in
     * the bucket chain . A ghostBucket is a placeholder for a set of unknown 
     * VLSNs.
     *
     * We use the following test logic to ensure the quality of VLSNBucket:
     *   1. Use forwardVLSNScanner and backwardVLSNScanner to travel through 
     *      the buckets.
     *   2. In forward scanning, if the missing VLSN is visited, and this 
     *      missing VLSN is not in the ghostBucket, the previous LSN will be 
     *      returned. If the missing VLSN is in the ghostBucket, the LSN_NULL 
     *      will be returned. 
     *   3. In backward scanning, if the missing VLSN (no matter in the 
     *      ghostBucket or not) is visited, the LSN_NULL will be returned. 
     *   4. Make sure no exception will be thrown during the scanning. Any 
     *      exception means that there are some problems in the VLSNBuckt 
     *      scanning process.
     */
    @Test
    public void testNonContiguousGhostBucket() 
        throws Throwable { 
        
        /* Use a standalone env to simplify the test - no rep env required. */
        Environment env = makeEnvironment();
        VLSNIndex vlsnIndex = null;
        DatabaseImpl mappingDbImpl = null;
        Database mappingDb = null;
        /* The JE database log file (.jdb file) number. */
        long fileNum = 33;     
        /* The offset between two contiguous VLSN in log file. */        
        long offset = 100;     
        /* The stride between two neighbor VLSN sequence in one bucket. */        
        int stride = 3;   
        /* The max number of VLSN->LSN mappings in one bucket. */        
        int maxMappings = 4;    
        /* The max distance between two neighbor VLSNs in one bucket. */        
        int maxDist = 1000;    
        /* The number of VLSN entries in one bucket. */        
        int numEntries = 40;
        
        try {     

            /* 
             * Create a vlsnIndex with the given stride, maxMappings and 
             * maxDist, under the given environment and mapping database.
             */
            vlsnIndex = new VLSNIndex(DbInternal.getEnvironmentImpl(env),
                                      testMapDb, new NameIdPair("n1",1),
                                      stride, maxMappings, maxDist,
                                      new RecoveryInfo()); 
                                      
            /* Get the mapping database. */                         
            DatabaseConfig dbConfig = new DatabaseConfig();
            mappingDb = env.openDatabase(null, testMapDb, dbConfig);
            mappingDbImpl = DbInternal.getDatabaseImpl(mappingDb);
            
            /* 
             * Create a GostBucket for bucket1. This ghostBucket represents the
             * unknown VLSNs 1-12 at the beginning of the VLSN range.
             */
            VLSNBucket placeholder = new GhostBucket
                                            (new VLSN(1), 
                                             DbLsn.makeLsn(fileNum, offset), 
                                             13 * offset);
            TransactionConfig config = new TransactionConfig();
            config.setDurability(Durability.COMMIT_NO_SYNC);
            Txn txn = Txn.createLocalTxn(DbInternal.getEnvironmentImpl(env), 
                                         config);
            boolean success = false;
            try {
                /* Write the GhostBucket to the mapping database. */
                placeholder.writeToDatabase(DbInternal.getEnvironmentImpl(env),
                                            mappingDbImpl, txn);
                success = true;
            } finally {
                if (success) {
                    txn.commit();
                } else {
                    txn.abort();
                }
            }
            
            /*
             * We create holes in the buckets, each hole misses one VLSN (In 
             * this test case, the missing VLSN is 24, 36). 
             * 
             * With the strides and holes, we expect the buckets to end up like
             *   Bucket 1 = GhostBucket (has been put before)
             *   Bucket 2 = { vlsn = 13, 16, 19, 22, 23 }
             *   Bucket 3 = { vlsn = 25, 28, 31, 34, 35 }
             *   Bucket 4 = { vlsn = 37, 40 }
             */            
            for (int i = 13; i <= numEntries; i++) {
                if(i != 24 && i != 36) {
                    putEntryToVLSNIndex(i, fileNum, offset, vlsnIndex);
                }
            }      
            
            /* 
             * We expect these mappings. These expected mappings assist in 
             * checking the correctness of the scanning process.
             */
            long[] expectedVLSN = { 
                13, 16, 19, 22, 23, 25, 
                28, 31, 34, 35, 37, 40 
            };
            TreeMap<VLSN, Long> expected = new TreeMap<VLSN, Long>(); 
            makeExpectedMapping(expected, expectedVLSN, fileNum, offset);
                
            VLSNRange range = vlsnIndex.getRange();
            assertEquals(expected.firstKey(), range.getFirst());
            assertEquals(expected.lastKey(), range.getLast());
            
            /* Scanning the VLSN buckets forwards and backwards. */
            for(int i = 1; i <= numEntries; i++) {
                forwardScanning(vlsnIndex, numEntries, expected, i);
                backwardScanning(vlsnIndex, expected, i);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        } finally {
            if (vlsnIndex != null) {
                vlsnIndex.close();
            }
            if (mappingDb != null) {
                mappingDb.close();
            }
            env.removeDatabase(null, testMapDb);
            env.close();
        }
    }
    
    /* Generate a TestLogItem and insert it into the VLSNIndex. */    
    private void putEntryToVLSNIndex(int pos,
                                     long fileNum,
                                     long offset,
                                     VLSNIndex vlsnIndex) 
        throws Throwable {

        VLSN vlsn = new VLSN(pos);
        long lsn = DbLsn.makeLsn(fileNum, pos * offset);
        /* We create TestLogItems with the VLSN->LSN mappings. */ 
        TestLogItem logItem = new TestLogItem
            (vlsn, lsn, LogEntryType.LOG_INS_LN_TRANSACTIONAL.getTypeNum());
        vlsnIndex.put(logItem);
    }
    
    /* Generate the expected VLSN->LSN mapping. */
    private void makeExpectedMapping(TreeMap<VLSN, Long> expected,
                                     long[] vlsnSet,
                                     long fileNum,
                                     long offSet) {
        assert(expected != null) : "expected TreeMap is null";
        for(int i = 0; i < vlsnSet.length; i++) {
            expected.put(new VLSN(vlsnSet[i]), 
                         DbLsn.makeLsn(fileNum, vlsnSet[i] * offSet));
        }
    }
    
    /* Scan the VLSN buckets forwards. */
    private void forwardScanning(VLSNIndex vlsnIndex, 
                                 int numEntries,
                                 TreeMap<VLSN, Long> expected,
                                 int startVLSN) {
        Long startLsn; 
        ForwardVLSNScanner fScanner = new ForwardVLSNScanner(vlsnIndex);
        startLsn = fScanner.getStartingLsn(new VLSN(startVLSN));
        
        /* 
         * expectedStartVLSN is not equal to startVLSN, when the startVLSN is
         * in the gap. For example, if there are buckets (1,3,5) and (7,9,10), 
         * the startVLSN is 6, then the expectedStartVLSN should be 5.
         * expectedStartVLSN is found in the expected mapping set.
         */
        long expectedStartVLSN;
        boolean ifGhostBucket = true;
        /* Find the expectedStartVLSN forward in the expected mapping*/
        for(expectedStartVLSN = startVLSN; expectedStartVLSN >= 1; 
            expectedStartVLSN--) {
            Long expectedLsn = expected.get(new VLSN(expectedStartVLSN));
            if(expectedLsn != null) {
                /* We have found the expectedStartVLSN. */
                ifGhostBucket = false;
                break;
            }
        }
        
        /* 
         * One of the motivation of this test:
         * Suppose the vlsn index and buckets are (1,3,5) and (7,9,10) and 
         * there is a forward scan. the correct scan should be:
         * getStartingLsn(3) would return the lsn for vlsn 3,
         * getStartingLsn(4) would return the lsn for vlsn 3,
         * getStartingLsn(5) would return the lsn for vlsn 5, 
         * getStartingLsn(6) would return the lsn for vlsn 5, rather than 7.
         *
         * The startVLSN is not in the ghostBucket.
         */
        if (!ifGhostBucket) {
            assertEquals(expected.get(new VLSN(expectedStartVLSN)), startLsn);
        } else {      
            /* The startVLSN is in the ghostBucket*/
            VLSNBucket bucket = 
                vlsnIndex.getLTEBucketFromDatabase(new VLSN(startVLSN));
            assertEquals(GhostBucket.class, bucket.getClass());
        }
        
        /* Start forward scanning from the found startVLSN. */
        for (long i = startVLSN; i <= numEntries; i++) {
            VLSN vlsn = new VLSN(i);
            Long expectedLsn = expected.get(vlsn);
            Long scannerLsn = fScanner.getPreciseLsn(vlsn);

            if (expectedLsn == null) {
                assertEquals((Long) DbLsn.NULL_LSN, scannerLsn);
                
                /* 
                 * If there's no exact match, approximate search should
                 * return the one just previous. If the VLSN is in the
                 * ghostBucket, there is no any previous VLSN in the expected
                 * mapping set.
                 */
                Long prevLsn = null;
                for (long find = i - 1; find >= 0; find--) {
                    prevLsn = expected.get(new VLSN(find));
                    if (prevLsn != null)
                        break;
                }
                
                /* If the vlsn is not in a ghostbucket. */
                if(prevLsn != null) {
                    assertEquals(prevLsn, 
                                 (Long) fScanner.getApproximateLsn(vlsn));
                } else {    
                    /* If the vlsn is in a ghostbucket. */
                    VLSNBucket bucket = 
                        vlsnIndex.getLTEBucketFromDatabase(vlsn);
                    assertEquals(GhostBucket.class, bucket.getClass());
                }
            } else {
                assertEquals(expectedLsn, scannerLsn);
                assertEquals
                    (expectedLsn, (Long) fScanner.getApproximateLsn(vlsn));
            }
        }
    }
    
    /* Scan the VLSN buckets backwards. */
    private void backwardScanning(VLSNIndex vlsnIndex, 
                                  TreeMap<VLSN, Long> expected,
                                  int startVLSN) {
        Long startLsn;       
        BackwardVLSNScanner bScanner =  new BackwardVLSNScanner(vlsnIndex);
        startLsn = bScanner.getStartingLsn(new VLSN(startVLSN));
        
        /* 
         * expectedStartVLSN is not equal to startVLSN, when the startVLSN is
         * in the gap. For example, if there are buckets (1,3,5) and (7,9,10),
         * the startVLSN is 6, then the expectedStartVLSN should be 7.
         * expectedStartVLSN is found in the expected mapping set.
         */
        long expectedStartVLSN;
        boolean ifGhostBucket = true;
        /* Find the expectedStartVLSN backward in the expected mapping*/
        for(expectedStartVLSN = startVLSN; 
            expectedStartVLSN <= expected.lastKey().getSequence(); 
            expectedStartVLSN++) {
            Long expectedLsn = expected.get(new VLSN(expectedStartVLSN));
            if(expectedLsn != null) {
                /* We have found the expectedStartVLSN. */
                ifGhostBucket = false;
                break;
            }
        }
        
        /* 
         * One of the motivation of this test:
         * Suppose the vlsn index and buckets are (1,3,5) and (7,9,10) and 
         * there is a forward scan. the correct scan should be:
         * getStartingLsn(9) would return the lsn for vlsn 9,
         * getStartingLsn(8) would return the lsn for vlsn 9,
         * getStartingLsn(7) would return the lsn for vlsn 7, 
         * getStartingLsn(6) would return the lsn for vlsn 7, rather than 5
         *
         * The startVLSN is not in the ghostBucket.
         */
        if (!ifGhostBucket) {
            assertEquals(expected.get(new VLSN(expectedStartVLSN)), startLsn);
        } else {      
            /* The startVLSN is in the ghostBucket*/
            VLSNBucket bucket = 
                vlsnIndex.getLTEBucketFromDatabase(new VLSN(startVLSN));
            assertEquals(GhostBucket.class, bucket.getClass());
        }
        
        /* Start backward scanning from the decided startVLSN. */
        for (long i = startVLSN; i >= 1; i --) {
            VLSN vlsn = new VLSN(i);
            Long expectedLsn = expected.get(vlsn);
            Long scannerLsn = bScanner.getPreciseLsn(vlsn);
            if (expectedLsn == null) {
                assertEquals((Long)DbLsn.NULL_LSN, scannerLsn);
                
                /* Judge if the vlsn is in a ghostbucket. */
                Long prevLsn = null;
                for (long find = i - 1; find >= 1; find--) {
                    prevLsn = expected.get(new VLSN(find));
                    if (prevLsn != null)
                        break;
                }
                
                /* 
                 * If the vlsn is in a ghostbucket, there is no any previous 
                 * VLSN in the expected mapping set.
                 */
                if(prevLsn == null) {
                    VLSNBucket bucket = 
                        vlsnIndex.getLTEBucketFromDatabase(vlsn);
                    assertEquals(GhostBucket.class, bucket.getClass());
                }
            } else {
                assertEquals(expectedLsn, scannerLsn);
            }
        }
    }

    /**
     * Tests a timing window when a VLSNIndex flush occurs during a call to
     * VLSNIndex.getGTEBucket, which resulted in a "Can't Find GTE Bucket for
     * VLSN XXX" error. See the SR for details.
     */
    @Test
    public void testSR20726GTESearch() throws Throwable {

        int stride = 5;
        int maxMappings = 2;
        int maxDist = 1000;

        Environment env = makeEnvironment();
        VLSNIndex vlsnIndex = null;

        try {
            vlsnIndex = new VLSNIndex(DbInternal.getEnvironmentImpl(env),
                                      testMapDb, new NameIdPair("n1",1),
                                      stride, maxMappings, maxDist,
                                      new RecoveryInfo());

            /*
             * Put some mappings in. With the strides, we expect them to
             * end up in these buckets.
             * Bucket 1 = vlsn 1, 6, 10
             * Bucket 2 = vlsn 11, 16, 20,
             * Bucket 3 = vlsn 21, 25
             */
            for (int i = 1; i <= 25; i++) {
                putEntryToVLSNIndex(i, 33, 100, vlsnIndex);
            }

            /* Make them persistent. */
            vlsnIndex.flushToDatabase(Durability.COMMIT_SYNC);
            VLSN target = new VLSN(22);
            VLSNBucket foundBucket = vlsnIndex.getGTEBucket(target, null);
            assertEquals(new VLSN(21), foundBucket.getFirst());
            assertEquals(new VLSN(25), foundBucket.getLast());

            /*
             * Add more mappings to tracker which start a different bucket.
             * This bucket will be found in the tracker; it hasn't been
             * flushed.
             *
             * Bucket 4 = vlsn 26, 30
             */
            for (int i = 26; i <= 30; i++) {
                putEntryToVLSNIndex(i, 34, 100, vlsnIndex);
            }
            foundBucket = vlsnIndex.getGTEBucket(target, null);
            assertEquals(new VLSN(21), foundBucket.getFirst());
            assertEquals(new VLSN(25), foundBucket.getLast());

            /*
             * Now provoke a call to flushToDatabase while we call
             * getGTEBucket. This mimics what happens when a feeder is running
             * and a checkpointer flushes the index. Before SR 20726 was fixed,
             * this resulted in an EnvironmentFailureException out of the
             * getGTEBucket call. 
             */
            FlushVLSNIndex hook = new FlushVLSNIndex(vlsnIndex);
            vlsnIndex.setGTEHook(hook);
            foundBucket = vlsnIndex.getGTEBucket(target, null);
            assertEquals(new VLSN(21), foundBucket.getFirst());
            assertEquals(new VLSN(25), foundBucket.getLast());
            assertTrue(hook.wasExecuted());

        } finally {
            if (vlsnIndex != null) {
                vlsnIndex.close();
            }

            env.removeDatabase(null, testMapDb);
            env.close();
        }
    }

    /** Force a flush of the vlsn index. */
    private static class FlushVLSNIndex implements TestHook<Object> {

        private final VLSNIndex index;
        private boolean executed;

        FlushVLSNIndex(VLSNIndex index) {
            this.index = index;
        }
         
        public void doHook() {
            index.flushToDatabase(Durability.COMMIT_SYNC);
            executed = true;
        }

        public boolean wasExecuted() {
            return executed;
        }

        public void hookSetup() {
        }

        public void doIOHook() throws IOException {
        }

        public void doHook(Object obj) {
        }

        public Object getHookValue() {
            return null;
        }
    }
}
