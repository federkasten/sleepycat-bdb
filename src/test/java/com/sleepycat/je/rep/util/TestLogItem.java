/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002,2008 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.util;

import com.sleepycat.je.log.LogEntryHeader;
import com.sleepycat.je.log.LogItem;
import com.sleepycat.je.utilint.VLSN;

/* Used to create placeholder log items for unit tests */

public class TestLogItem extends LogItem {

    public TestLogItem(VLSN vlsn, long lsn, byte entryType) {
        header = new LogEntryHeader(entryType, 1, 0, vlsn);
        newLsn = lsn;
    }
}