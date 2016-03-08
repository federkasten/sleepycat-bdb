/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.log;

import java.nio.ByteBuffer;

import com.sleepycat.je.log.entry.ReplicableLogEntry;

/**
 * A sub-interface of {@link Loggable} implemented by classes that can write
 * themselves to a byte buffer in the previous log format, for use by instances
 * of {@link ReplicableLogEntry} that need to support the previous log format
 * during replication.  See [#22336].
 *
 * <p>Classes that implement {@code Loggable} should implement this interface
 * if they are included in replication data.
 *
 * <p>Implementations are only required to support the immediately previous log
 * version.  To simplify code maintenance, implementations should only provide
 * support for writing the current and most recent previous versions, removing
 * support for the previous old version when a new version is introduced.
 *
 * <p>Implementing classes should document the version of the class's most
 * recent format change.  Log entry classes that contain {@code
 * VersionedWriteLoggable} items can use that information to determine if they
 * can copy the log contents for an entry directly or if they need to convert
 * them in order to be compatible with a particular log version.
 */
public interface VersionedWriteLoggable extends Loggable {

    /**
     * Returns the log version of the most recent format change for this
     * loggable item.
     *
     * @return the log version of the most recent format change
     */
    int getLastFormatChange();

    /**
     * Returns the number of bytes needed to store this object in the format
     * for the specified log version.  Only the current and immediately
     * previous log versions need to be supported, and the previous log version
     * only needs to be supported for log entries with format changes made in
     * {@link LogEntryType#LOG_VERSION_REPLICATE_PREVIOUS} or greater.
     *
     * @param logVersion the log version
     * @return the number of bytes to store this object for the log version
     * @throws IllegalArgumentException if the log version is not supported
     */
    int getLogSize(int logVersion);

    /**
     * Serializes this object into the specified buffer in the format for the
     * specified log version.  Only the current and immediately previous log
     * versions need to be supported, and the previous log version only needs
     * to be supported for log entries with format changes made in {@link
     * LogEntryType#LOG_VERSION_REPLICATE_PREVIOUS} or greater.
     *
     * @param logBuffer the destination buffer
     * @param logVersion the log version
     * @throws IllegalArgumentException if the log version is not supported
     */
    void writeToLog(ByteBuffer logBuffer, int logVersion);
}
