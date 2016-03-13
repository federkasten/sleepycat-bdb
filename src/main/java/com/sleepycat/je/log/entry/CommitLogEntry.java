/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.log.entry;

import com.sleepycat.je.log.LogEntryType;
import com.sleepycat.je.txn.TxnCommit;

/**
 * Log entry for a transaction commit.
 */
public class CommitLogEntry extends SingleItemReplicableEntry<TxnCommit> {

    /**
     * The log version number of the most recent change for this log entry,
     * including any changes to the format of the underlying {@link TxnCommit}
     * object.
     *
     * @see #getLastFormatChange
     */
    public static final int LAST_FORMAT_CHANGE = 8;

    /** Construct a log entry for reading a {@link TxnCommit} object. */
    public CommitLogEntry() {
        super(TxnCommit.class);
    }

    /** Construct a log entry for writing a {@link TxnCommit} object. */
    public CommitLogEntry(final TxnCommit commit) {
        super(LogEntryType.LOG_TXN_COMMIT, commit);
    }

    /**
     * @see ReplicableLogEntry#getLastFormatChange
     */
    @Override
    public int getLastFormatChange() {
        return LAST_FORMAT_CHANGE;
    }
}
