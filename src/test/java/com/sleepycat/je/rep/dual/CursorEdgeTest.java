/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.dual;

public class CursorEdgeTest extends com.sleepycat.je.CursorEdgeTest {

    /* Database in this test case is set non-transactional. */
    @Override
    public void testGetPrevNoDupWithEmptyTree() {
    }
}
