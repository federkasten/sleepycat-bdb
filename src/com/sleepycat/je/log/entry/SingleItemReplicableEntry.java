/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.log.entry;

import java.nio.ByteBuffer;

import com.sleepycat.je.log.LogEntryType;
import com.sleepycat.je.log.VersionedWriteLoggable;

/**
 * A basic implementation of a replicable log entry that has a single loggable
 * item and provides for writing in a single format by default.  Starting with
 * log version 9, entry classes whose log format has changed since the previous
 * log version will need to override the {@link #getSize} and {@link
 * #copyEntry} methods to support writing the entry in the previous log format.
 *
 * @param <T> the type of the loggable items in this entry
 */
abstract class SingleItemReplicableEntry<T extends VersionedWriteLoggable>
        extends SingleItemEntry<T> implements ReplicableLogEntry {

    /**
     * Creates an instance of this class for reading a log entry.
     *
     * @param logClass the class of the contained loggable item
     */
    SingleItemReplicableEntry(final Class<T> logClass) {
        super(logClass);
    }

    /**
     * Creates an instance of this class for writing a log entry.
     *
     * @param entryType the associated log entry type
     * @param the contained loggable item
     */
    SingleItemReplicableEntry(final LogEntryType entryType, final T item) {
        super(entryType, item);
    }

    /* Implement ReplicableLogEntry */

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException {@inheritDoc}
     */
    @Override
    public int getSize(final int logVersion) {
        return BaseReplicableEntry.getSize(this, logVersion);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException {@inheritDoc}
     */
    @Override
    public void writeEntry(final ByteBuffer logBuffer, final int logVersion) {
        BaseReplicableEntry.checkCurrentVersion(this, logVersion);
        assert getMainItem().getLastFormatChange() <= getLastFormatChange()
            : "Format of loggable newer than format of entry";
        getMainItem().writeToLog(logBuffer, logVersion);
    }
}
