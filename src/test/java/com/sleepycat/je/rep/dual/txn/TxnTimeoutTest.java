/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.dual.txn;


public class TxnTimeoutTest extends com.sleepycat.je.txn.TxnTimeoutTest {

    /* 
     * The following unit tests are excluded because they intentionally 
     * provoked to exceptions and handles those accordingly. The special 
     * handing is not available on the replica side, and would cause a replica
     * failure.
     */
    @Override
    public void testTxnTimeout() {
    }
    
    @Override
    public void testPerTxnTimeout() {
    }

    @Override
    public void testEnvTxnTimeout() {
    }

    @Override
    public void testEnvNoLockTimeout() {
    }

    @Override
    public void testPerLockTimeout() {
    }

    @Override
    public void testEnvLockTimeout() {
    }

    @Override
    public void testPerLockerTimeout() {
    }

    @Override
    public void testReadCommittedTxnTimeout() {
    }

    @Override
    public void testReadCommittedLockTimeout() {
    }

    @Override
    public void testSerializableTxnTimeout() {
    }

    @Override
    public void testSerializableLockTimeout() {
    }
}
