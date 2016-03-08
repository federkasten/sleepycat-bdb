/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.dual;

public class DatabaseComparatorsTest
    extends com.sleepycat.je.DatabaseComparatorsTest {

    /* Following test cases are setting non-transactional. */
    @Override
    public void testSR12517() {
    }

    @Override
    public void testDupsWithPartialComparator() {
    }

    @Override
    public void testDatabaseCompareKeysArgs() 
        throws Exception {
    }

    @Override
    public void testSR16816DefaultComparator() 
        throws Exception {
    }

    @Override
    public void testSR16816ReverseComparator() 
        throws Exception {
    }
}
