/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 */

package com.sleepycat.je.rep.dual.test;

import com.sleepycat.je.TransactionConfig;

public class PhantomTest extends com.sleepycat.je.test.PhantomTest {

    public PhantomTest(TransactionConfig txnConfig) {
        super(txnConfig);
    }
}
