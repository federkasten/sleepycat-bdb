/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.dual;

public class DatabaseTest extends com.sleepycat.je.DatabaseTest {

    /* Non-transactional tests. */

    @Override
    public void testOpenCursor() {
    }

    @Override
    public void testOpenCursorAfterEnvInvalidation() {
    }

    @Override
    public void testPreloadBytesExceedsCache() {
    }

    @Override
    public void testPreloadEntireDatabase() {
    }

    @Override
    public void testPutNoOverwriteInADupDbNoTxn() {
    }

    @Override
    public void testDeferredWriteDatabaseCount() {
    }

    @Override
    public void testDeferredWriteDatabaseCountDups() {
    }

    @Override
    public void testPreloadMultipleDatabases() {
    }

    /*
     * Replication disturbs the cache due to the presence of the feeder and as
     * a consequence invalidates the assumptions underlying the test.
     *
     * The following assertion fails:
     *
     *  assertTrue(postCreateResidentNodes >= postIterationResidentNodes)
     *
     * We could conditionalize just this assertion for replication.
     */
    @Override
    public void testPreloadByteLimit() {
    }

    @Override
    public void testPreloadTimeLimit() {
    }

    @Override
    public void testDbPreempted() {
    }
}
