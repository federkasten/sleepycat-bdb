/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.log.entry.LogEntry;
import com.sleepycat.util.test.TestBase;

public class LogEntryTest extends TestBase {

    @Test
    public void testEquality()
        throws DatabaseException {

        byte testTypeNum = LogEntryType.LOG_IN.getTypeNum();

        /* Look it up by type */
        LogEntryType foundType = LogEntryType.findType(testTypeNum);
        assertEquals(foundType, LogEntryType.LOG_IN);
        assertTrue(foundType.getSharedLogEntry() instanceof
                   com.sleepycat.je.log.entry.INLogEntry);

        /* Look it up by type */
        foundType = LogEntryType.findType(testTypeNum);
        assertEquals(foundType, LogEntryType.LOG_IN);
        assertTrue(foundType.getSharedLogEntry() instanceof
                   com.sleepycat.je.log.entry.INLogEntry);

        /* Get a new entry object */
        LogEntry sharedEntry = foundType.getSharedLogEntry();
        LogEntry newEntry = foundType.getNewLogEntry();

        assertTrue(sharedEntry != newEntry);
    }
}
