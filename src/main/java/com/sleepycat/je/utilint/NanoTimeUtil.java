/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002-2014 Oracle.  All rights reserved.
 *
 */

package com.sleepycat.je.utilint;


/**
 * Utility class for dealing with special cases of System.nanoTime
 */
public class NanoTimeUtil {

    /**
     * Special compare function for comparing times returned by
     * System.nanoTime() to protect against numerical overflows.
     *
     * @return a negative integer, zero, or a positive integer as the
     * first argument is less than, equal to, or greater than the second.
     *
     * @see System#nanoTime
     */
    public static long compare(long t1, long t2) {
        return t1 - t2;
    }
}
