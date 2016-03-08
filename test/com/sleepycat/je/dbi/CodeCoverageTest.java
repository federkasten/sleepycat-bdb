/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.dbi;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.sleepycat.je.DbInternal;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.util.StringDbt;

/**
 * Various unit tests for CursorImpl to enhance code coverage.
 */
public class CodeCoverageTest extends DbCursorTestBase {

    public CodeCoverageTest() {
        super();
    }

    /**
     * Test the internal CursorImpl.delete() deleted LN code..
     */
    @Test
    public void testDeleteDeleted()
        throws Throwable {

        try {
            initEnv(false);
            doSimpleCursorPuts();

            StringDbt foundKey = new StringDbt();
            StringDbt foundData = new StringDbt();

            OperationStatus status = cursor.getFirst(foundKey, foundData,
                                                     LockMode.DEFAULT);
            assertEquals(OperationStatus.SUCCESS, status);

            cursor.delete();
            cursor.delete();

            /*
             * While we've got a cursor in hand, call CursorImpl.dumpToString()
             */
            DbInternal.getCursorImpl(cursor).dumpToString(true);
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        }
    }
}
