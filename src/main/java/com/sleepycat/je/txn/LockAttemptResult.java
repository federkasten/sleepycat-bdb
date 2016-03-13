/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.txn;

/**
 * This is just a struct to hold a multi-value return.
 */
public class LockAttemptResult {
    public final boolean success;
    final Lock useLock;
    public final LockGrantType lockGrant;

    LockAttemptResult(Lock useLock,
                      LockGrantType lockGrant,
                      boolean success) {

        this.useLock = useLock;
        this.lockGrant = lockGrant;
        this.success = success;
    }
}
