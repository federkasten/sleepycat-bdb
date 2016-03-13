/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */
package com.sleepycat.je.rep.dual.tree;

public class MemorySizeTest extends com.sleepycat.je.tree.MemorySizeTest {

    /* 
     * This test changes the KeyPrefix on the master, but not on the replicas, 
     * so it would fail on the rep mode, disable it. 
     */
    @Override
    public void testKeyPrefixChange() {
    }
}
