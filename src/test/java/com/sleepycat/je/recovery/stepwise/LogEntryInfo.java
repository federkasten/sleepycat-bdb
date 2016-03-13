/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.recovery.stepwise;

import java.util.Map;
import java.util.Set;

import com.sleepycat.je.utilint.DbLsn;

/*
 * A LogEntryInfo supports stepwise recovery testing, where the log is
 * systemantically truncated and recovery is executed. At each point in a log,
 * there is a set of records that we expect to see. The LogEntryInfo
 * encapsulates enough information about the current log entry so we can
 * update the expected set accordingly.
 */

public class LogEntryInfo {
    private long lsn;
    int key;
    int data;

    LogEntryInfo(long lsn,
              int key,
              int data) {
        this.lsn = lsn;
        this.key = key;
        this.data = data;
    }

    /*
     * Implement this accordingly. For example, a LogEntryInfo which
     * represents a non-txnal LN record would add that key/data to the
     * expected set. A txnal delete LN record would delete the record
     * from the expecte set at commit.
     *
     * The default action is that the expected set is not changed.
     */
    public void updateExpectedSet
        (Set<TestData> expectedSet, 
         Map<Long, Set<TestData>> newUncommittedRecords, 
         Map<Long, Set<TestData>> deletedUncommittedRecords) {}

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("type=").append(this.getClass().getName());
        sb.append("lsn=").append(DbLsn.getNoFormatString(lsn));
        sb.append(" key=").append(key);
        sb.append(" data=").append(data);
        return sb.toString();
    }

    public long getLsn() {
        return lsn;
    }
}
