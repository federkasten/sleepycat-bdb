/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.util;

import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.sleepycat.util.test.TestBase;

/**
 * Checks the DbCacheSize returns consistent results by comparing the
 * calculated and measured values.  If this test fails, it probably means the
 * technique used by DbCacheSize for estimating or measuring has become
 * outdated or incorrect.  Or, it could indicate a bug in memory budget
 * calculations or IN memory management.  Try running DbCacheSize manually to
 * debug, using the cmd string for the test that failed.
 */
@RunWith(Parameterized.class)
public class DbCacheSizeTest extends TestBase {

    /*
     * It is acceptable for the measured values to be somewhat different than
     * the calculated values, due to differences in actual BIN density, for
     * example.  The measured values are typically smaller, I've noticed.
     */
    private static double ERROR_ALLOWED = 0.15;

    static final String[] COMMANDS = {
        /*0*/ "-records 100000 -key 10 -data 100",
        /*1*/ "-records 100000 -key 10 -data 100 -orderedinsertion",
        /*2*/ "-records 100000 -key 10 -data 100 -duplicates",
        /*3*/ "-records 100000 -key 10 -data 100 -duplicates " +
              "-orderedinsertion",
        /*4*/ "-records 100000 -key 10 -data 100 -nodemax 250",
        /*5*/ "-records 100000 -key 10 -data 100 -nodemax 250 " +
              "-orderedinsertion",
        /*6*/ "-records 100000 -key 20 -data 100 -keyprefix 10",
        /*7*/ "-records 100000 -key 20 -data 100 -keyprefix 2 " +
              "-je.tree.compactMaxKeyLength 19",
        /*8*/ "-records 100000 -key 10 -data 100 -replicated",
        /*9*/ "-records 100000 -key 10 -data 100 -replicated " +
              "-je.rep.preserveRecordVersion true",
    };

    private String cmd;
    private int testNum;

    @Parameters
    public static List<Object[]> genParams() {
       List<Object[]> list = new ArrayList<Object[]>();
       int i = 0;
       for (String cmd : COMMANDS) {
           list.add(new Object[]{cmd, i});
           i++;
       }
       
       return list;
    }
    
    public DbCacheSizeTest(String cmd, int testNum){
        this.cmd = cmd;
        this.testNum = testNum;
        customName = "-" + testNum;;
       
    }
    
    @Test
    public void testSize() {

        /* Get estimated cache sizes and measured sizes. */
        final String[] args = (cmd + " -measure").split(" ");
        DbCacheSize util = new DbCacheSize();
        try {
            util.parseArgs(args);
            util.calculateCacheSizes();
            util.measure(null);
        } finally {
            util.cleanup();
        }

        /*
         * Check that calculated and measured sizes are within some error
         * tolerance.  We use the min calculated size; it more closely matches
         * the measure size, because LSN compaction is in effect when measuring
         * the relatively small data sets in the test.
         */
        check("measuredBtreeSize", util.getMinBtreeSize(),
               util.getMeasuredBtreeSize(), ERROR_ALLOWED);
        check("measuredBtreeSizeWithData", util.getMinBtreeSizeWithData(),
               util.getMeasuredBtreeSizeWithData(), ERROR_ALLOWED);
    }

    private void check(String name,
                       double expected,
                       double actual,
                       double errorAllowed) {
        if ((Math.abs(expected - actual) / expected) > errorAllowed) {
            fail("Error allowed (" + errorAllowed + ") is exceeded for " +
                 name + ", expected=" + expected + ", actual=" + actual +
                 ", testNum=" + testNum);
        }
    }
}
