/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.dual.persist.test;

import java.util.List;

import org.junit.runners.Parameterized.Parameters;

import com.sleepycat.persist.model.DeleteAction;

public class ForeignKeyTest extends com.sleepycat.persist.test.ForeignKeyTest {

    public ForeignKeyTest(String type, DeleteAction action, String label,
            String useClassLabel) {
        super(type, action, label, useClassLabel);
    }
    
    @Parameters
    public static List<Object[]> genParams() {
        return paramsHelper(true);
    }
}
