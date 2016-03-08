/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.dual.test;

import java.util.List;

import org.junit.runners.Parameterized.Parameters;

import com.sleepycat.util.test.TxnTestCase;

public class AtomicPutTest extends com.sleepycat.je.test.AtomicPutTest {

    public AtomicPutTest(String txnType) {
        super(txnType);
    }
    
    @Parameters
    public static List<Object[]> genParams() {
        return getTxnParams(new String[] {TxnTestCase.TXN_USER}, true);
    }
}
