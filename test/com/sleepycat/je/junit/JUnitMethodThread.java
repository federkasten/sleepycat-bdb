/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.junit;

import java.lang.reflect.Method;

/**
 * A JUnitThread whose testBody calls a given TestCase method.
 */
public class JUnitMethodThread extends JUnitThread {

    private Object testCase;
    private Method method;
    private Object param;

    public JUnitMethodThread(String threadName, String methodName,
                             Object testCase)
        throws NoSuchMethodException {

        this(threadName, methodName, testCase, null);
    }

    public JUnitMethodThread(String threadName, String methodName,
                             Object testCase, Object param)
        throws NoSuchMethodException {

        super(threadName);
        this.testCase = testCase;
        this.param = param;
        method = testCase.getClass().getMethod(methodName, new Class[0]);
    }

    public void testBody()
        throws Exception {

        if (param != null) {
            method.invoke(testCase, new Object[] { param });
        } else {
            method.invoke(testCase, new Object[0]);
        }
    }
}
