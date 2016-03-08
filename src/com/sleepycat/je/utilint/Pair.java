/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.utilint;

/**
 * Generic immutable pair, intended for grouping two data elements when a more
 * specific class is unwarranted.
 */
public class Pair<FIRST, SECOND> {
    private final FIRST first;
    private final SECOND second;

    public Pair(FIRST first, SECOND second) {
        this.first = first;
        this.second = second;
    }

    public FIRST first() {
        return first;
    }
    
    public SECOND second() {
        return second;
    }
}
