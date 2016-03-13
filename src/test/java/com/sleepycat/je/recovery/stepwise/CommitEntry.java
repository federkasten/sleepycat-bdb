/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.recovery.stepwise;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/*
 * A Commit entry signals that some records should be moved from the
 * not-yet-committed sets to the expected set.
 */
public class CommitEntry extends LogEntryInfo {
    private long txnId;

    CommitEntry(long lsn, long txnId) {
        super(lsn, 0, 0);
        this.txnId = txnId;
    }

    @Override
    public void updateExpectedSet
        (Set<TestData>  useExpected,
         Map<Long, Set<TestData>> newUncommittedRecords,
         Map<Long, Set<TestData>> deletedUncommittedRecords) {

        Long mapKey = new Long(txnId);

        /* Add any new records to the expected set. */
        Set<TestData> records = newUncommittedRecords.get(mapKey);
        if (records != null) {
            Iterator<TestData> iter = records.iterator();
            while (iter.hasNext()) {
                useExpected.add(iter.next());
            }
        }

        /* Remove any deleted records from expected set. */
        records = deletedUncommittedRecords.get(mapKey);
        if (records != null) {
            Iterator<TestData> iter = records.iterator();
            while (iter.hasNext()) {
                useExpected.remove(iter.next());
            }
        }
    }
}
