/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.dual.txn;

import java.util.List;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TxnMemoryTest extends com.sleepycat.je.txn.TxnMemoryTest {

    public TxnMemoryTest(String testMode, String eMode) {
        super(testMode, eMode);
    }
    
    @Parameters
    public static List<Object[]> genParams() {
        
        return paramsHelper(true);
    }
}
