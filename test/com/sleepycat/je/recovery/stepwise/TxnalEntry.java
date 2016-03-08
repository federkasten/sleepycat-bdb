/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.recovery.stepwise;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.DatabaseEntry;

/*
 * A Transactional log entry should add put itself
 * into the not-yet-committed set.
 */

public class TxnalEntry extends LogEntryInfo {
    private long txnId;

    TxnalEntry(long lsn,
               int key,
               int data,
               long txnId) {
        super(lsn, key, data);
        this.txnId = txnId;
    }

    /* Implement this accordingly. For example, a LogEntryInfo which
     * represents a non-txnal LN record would add that key/data to the
     * expected set. A txnal delete LN record would delete the record
     * from the expecte set at commit time.
     */
    @Override
    public void updateExpectedSet
        (Set<TestData> useExpected,
         Map<Long, Set<TestData>> newUncommittedRecords,
         Map<Long, Set<TestData>> deletedUncommittedRecords) {

        DatabaseEntry keyEntry = new DatabaseEntry();
        DatabaseEntry dataEntry = new DatabaseEntry();

        IntegerBinding.intToEntry(key, keyEntry);
        IntegerBinding.intToEntry(data, dataEntry);

        Long mapKey = new Long(txnId);
        Set<TestData> records = newUncommittedRecords.get(mapKey);
        if (records == null) {
            records = new HashSet<TestData>();
            newUncommittedRecords.put(mapKey, records);
        }
        records.add(new TestData(keyEntry, dataEntry));
    }
}
