/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.dual;

public class TruncateTest extends com.sleepycat.je.TruncateTest {

    // TODO: relies on exact standalone LN counts. Rep introduces additional
    // LNs.
    @Override
    public void testEnvTruncateAbort() {
    }

    @Override
    public void testEnvTruncateCommit() {
    }

    @Override
    public void testEnvTruncateAutocommit() {
    }

    @Override
    public void testEnvTruncateNoFirstInsert() {
    }

    // Skip since it's non-transactional
    @Override
    public void testNoTxnEnvTruncateCommit() {
    }

    @Override
    public void testTruncateCommit() {
    }

    @Override
    public void testTruncateCommitAutoTxn() {
    }

    @Override
    public void testTruncateEmptyDeferredWriteDatabase() {
    }

    // TODO: Complex setup -- replicators not shutdown resulting in an
    // attempt to rebind to an already bound socket address
    @Override
    public void testTruncateAfterRecovery() {
    }

    /* Non-transactional access is not supported by HA. */
    @Override
    public void testTruncateNoLocking() {
    }

    /* Calls EnvironmentImpl.abnormalShutdown. */
    @Override
    public void testTruncateRecoveryWithoutMapLNDeletion()
        throws Throwable {
    }

    /* Calls EnvironmentImpl.abnormalShutdown. */
    @Override
    public void testTruncateRecoveryWithoutMapLNDeletionNonTxnal()
        throws Throwable {
    }

    /* Calls EnvironmentImpl.abnormalShutdown. */
    @Override
    public void testRemoveRecoveryWithoutMapLNDeletion()
        throws Throwable {
    }

    /* Calls EnvironmentImpl.abnormalShutdown. */
    @Override
    public void testRemoveRecoveryWithoutMapLNDeletionNonTxnal()
        throws Throwable {
    }

    /* Calls EnvironmentImpl.abnormalShutdown. */
    @Override
    public void testRecoveryRenameMapLNDeletion()
        throws Throwable {
    }
}
