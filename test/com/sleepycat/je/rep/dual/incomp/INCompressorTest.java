/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */
package com.sleepycat.je.rep.dual.incomp;


public class INCompressorTest 
    extends com.sleepycat.je.incomp.INCompressorTest {

    /* The following test cases are non-transactional. */
    @Override
    public void testDeleteTransactional() {
    }

    @Override
    public void testDeleteNonTransactional() {
    }

    @Override
    public void testDeleteNonTransactionalWithBinDeltas() {
    }

    @Override
    public void testDeleteDuplicate() {
    }

    @Override
    public void testRemoveEmptyBIN() {
    }

    @Override
    public void testRemoveEmptyBINWithBinDeltas() {
    }

    @Override
    public void testRemoveEmptyDBIN() {
    }

    @Override
    public void testRemoveEmptyDBINandBIN() {
    }

    @Override
    public void testRollForwardDelete() {
    }

    @Override
    public void testRollForwardDeleteDuplicate() {
    }

    @Override
    public void testLazyPruning() {
    }

    @Override
    public void testLazyPruningDups() {
    }

    @Override
    public void testEmptyInitialDBINScan() {
    }

    @Override
    public void testEmptyInitialBINScan() {
    }

    @Override
    public void testNodeNotEmpty() {
    }

    @Override
    public void testAbortInsert() {
    }

    @Override
    public void testAbortInsertDuplicate() {
    }

    @Override
    public void testRollBackInsert() {
    }

    @Override
    public void testRollBackInsertDuplicate() {
    }
}
