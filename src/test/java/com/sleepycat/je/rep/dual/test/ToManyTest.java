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

public class ToManyTest extends com.sleepycat.je.test.ToManyTest {

    public ToManyTest(String type) {
        super(type);
    }
    
    @Parameters
    public static List<Object[]> genParams() {
        return getTxnParams(
            new String[] {TxnTestCase.TXN_USER, TxnTestCase.TXN_AUTO}, true);
    }
}
