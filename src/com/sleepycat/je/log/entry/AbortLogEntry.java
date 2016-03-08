/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.log.entry;

import com.sleepycat.je.log.LogEntryType;
import com.sleepycat.je.txn.TxnAbort;

/**
 * Log entry for a transaction abort.
 */
public class AbortLogEntry extends SingleItemReplicableEntry<TxnAbort> {

    /**
     * The log version number of the most recent change for this log entry,
     * including any changes to the format of the underlying {@link TxnAbort}
     * object.
     *
     * @see #getLastFormatChange
     */
    public static final int LAST_FORMAT_CHANGE = 8;

    /** Construct a log entry for reading a {@link TxnAbort} object. */
    public AbortLogEntry() {
        super(TxnAbort.class);
    }

    /** Construct a log entry for writing a {@link TxnAbort} object. */
    public AbortLogEntry(final TxnAbort abort) {
        super(LogEntryType.LOG_TXN_ABORT, abort);
    }

    /**
     * @see ReplicableLogEntry#getLastFormatChange
     */
    @Override
    public int getLastFormatChange() {
        return LAST_FORMAT_CHANGE;
    }
}
