/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.dual;

public class DatabaseConfigTest extends com.sleepycat.je.DatabaseConfigTest {

    /* Environment in this test case is set non-transactional. */
    @Override
    public void testConfig() {
    }

    /* Environment in this test case is set non-transactional. */
    @Override
    public void testConfigConflict() {
    }

    /* Database in this test case is set non-transactional. */
    @Override
    public void testIsTransactional()  {
    }

    /* Environment in this test case is set non-transactional. */
    @Override
    public void testExclusive()  {
    }

    /* Environment in this test case is set non-transactional. */
    @Override
    public void testConfigOverrideUpdateSR15743() {
    }
}
