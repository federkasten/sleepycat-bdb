/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.dual.test;

import java.util.List;

import org.junit.runners.Parameterized.Parameters;

public class ForeignKeyTest extends com.sleepycat.je.test.ForeignKeyTest {

    public ForeignKeyTest(String type, boolean multiKey) {
        super(type, multiKey);
    }
    
    @Parameters
    public static List<Object[]> genParams() {
      return paramsHelper(true);
    }
}
