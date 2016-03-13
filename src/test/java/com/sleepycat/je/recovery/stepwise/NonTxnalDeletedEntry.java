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

import com.sleepycat.bind.tuple.IntegerBinding;

/*
 * A non-transactional log entry should add itself to the expected set.
 */

class NonTxnalDeletedEntry extends LogEntryInfo {
    NonTxnalDeletedEntry(long lsn,
                  int key,
                  int data) {
        super(lsn, key, data);
    }

    /* Delete this item from the expected set. */
    @Override
    public void updateExpectedSet
        (Set<TestData> useExpected, 
         Map<Long, Set<TestData>> newUncommittedRecords, 
         Map<Long, Set<TestData>>  deletedUncommittedRecords) {

        Iterator<TestData> iter = useExpected.iterator();
        while (iter.hasNext()) {
            TestData setItem = iter.next();
            int keyValInSet = IntegerBinding.entryToInt(setItem.getKey());
            if (keyValInSet == key) {
                if (data == -1) {
                    /* non-dup case, remove the matching key. */
                    iter.remove();
                    break;
                } else {
                    int dataValInSet = 
                        IntegerBinding.entryToInt(setItem.getData());
                    if (dataValInSet == data) {
                        iter.remove();
                        break;
                    }
                }
            }
        }
    }
}
