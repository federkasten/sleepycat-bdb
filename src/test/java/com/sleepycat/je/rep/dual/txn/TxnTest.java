/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.dual.txn;

public class TxnTest extends com.sleepycat.je.txn.TxnTest {

    @Override
    public void testBasicLocking()
        throws Throwable {
    }

    /* 
     * This test case is excluded because it uses the deprecated durability
     * API, which is prohibited in dual mode tests.
     */
    @Override
    public void testSyncCombo() 
        throws Throwable {
    }

    /**
     * Excluded because it opens and closes the environment several times and
     * the rep utils don't behave well under these conditions.
     */
    @Override
    public void testPossiblyCommittedState() {
    }
}
