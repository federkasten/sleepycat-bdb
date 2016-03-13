/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.log.entry;

import com.sleepycat.je.log.LogEntryType;
import com.sleepycat.je.utilint.Matchpoint;

/**
 * Log entry for a matchpoint object.
 */
public class MatchpointLogEntry extends SingleItemReplicableEntry<Matchpoint> {

    /**
     * The log version number of the most recent change for this log entry,
     * including any changes to the format of the underlying {@link Matchpoint}
     * object.
     *
     * @see #getLastFormatChange
     */
    public static final int LAST_FORMAT_CHANGE = 8;

    /** Construct a log entry for reading a {@link Matchpoint} object. */
    public MatchpointLogEntry() {
        super(Matchpoint.class);
    }

    /** Construct a log entry for writing a {@link Matchpoint} object. */
    public MatchpointLogEntry(final Matchpoint matchpoint) {
        super(LogEntryType.LOG_MATCHPOINT, matchpoint);
    }

    /**
     * @see ReplicableLogEntry#getLastFormatChange
     */
    @Override
    public int getLastFormatChange() {
        return LAST_FORMAT_CHANGE;
    }
}
