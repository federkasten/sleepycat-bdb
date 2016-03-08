/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.dbi;

import static org.junit.Assert.assertFalse;

import java.util.Enumeration;
import java.util.Hashtable;

import org.junit.Test;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbTestProxy;
import com.sleepycat.je.tree.BIN;

public class DbCursorDuplicateValidationTest extends DbCursorTestBase {

    public DbCursorDuplicateValidationTest() {
        super();
    }

    @Test
    public void testValidateCursors()
        throws Throwable {

        initEnv(true);
        Hashtable dataMap = new Hashtable();
        createRandomDuplicateData(10, 1000, dataMap, false, false);

        Hashtable bins = new Hashtable();

        DataWalker dw = new DataWalker(bins) {
                void perData(String foundKey, String foundData)
                    throws DatabaseException {
                    CursorImpl cursorImpl =
                        DbTestProxy.dbcGetCursorImpl(cursor);
                    BIN lastBin = cursorImpl.getBIN();
                    if (rnd.nextInt(10) < 8) {
                        cursor.delete();
                    }
                    dataMap.put(lastBin, lastBin);
                }
            };
        dw.setIgnoreDataMap(true);
        dw.walkData();
        dw.close();
        Enumeration e = bins.keys();
        while (e.hasMoreElements()) {
            BIN b = (BIN) e.nextElement();
            assertFalse(b.getCursorSet().size() > 0);
        }
    }
}
