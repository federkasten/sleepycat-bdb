/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.log;

import java.nio.ByteBuffer;

/**
 * A basic implementation of {@link VersionedWriteLoggable} that provides for
 * writing in a single format by default.  Starting with log version 9, as
 * specified by {@link LogEntryType#LOG_VERSION_REPLICATE_PREVIOUS}, loggable
 * classes whose log format has changed since the previous log version will
 * need to override the {@link #getLogSize} and {@link #writeToLog} methods to
 * support writing the entry in the previous log format.
 */
public abstract class BasicVersionedWriteLoggable
        implements VersionedWriteLoggable {

    /**
     * Creates an instance of this class.
     */
    public BasicVersionedWriteLoggable() {
    }

    /* Implement VersionedWriteLoggable */

    /* Subclasses must implement VersionedWriteLoggable.getLastFormatChange */

    /**
     * @see VersionedWriteLoggable#getLogSize(int)
     */
    @Override
    public int getLogSize(final int logVersion) {
        return getLogSize(this, logVersion);
    }

    /**
     * Implement {@link #getLogSize(int)} by checking that the requested log
     * version is not older than the loggable object's last format change, and
     * returning the object's current log size.  This method is provided to
     * simplify the implementation of {@link VersionedWriteLoggable} by
     * classes that do not subclass this class.
     *
     * @param loggable the loggable
     * @param logVersion the log version
     * @return the number of bytes to store the object for the log version
     * @throws IllegalArgumentException if the log version is not supported
     */
    public static int getLogSize(final VersionedWriteLoggable loggable,
                                 final int logVersion) {
        checkCurrentVersion(loggable, logVersion);
        return loggable.getLogSize();
    }

    /**
     * @see VersionedWriteLoggable#writeToLog(ByteBuffer, int)
     */
    @Override
    public void writeToLog(final ByteBuffer logBuffer, final int logVersion) {
        writeToLog(this, logBuffer, logVersion);
    }

    /**
     * Implement {@link #writeToLog(ByteBuffer, int)} by checking that the
     * requested log version is not older than the loggable object's last
     * format change, and writing the object in the current log format.  This
     * method is provided to simplify the implementation of {@link
     * VersionedWriteLoggable} by classes that do not subclass this class.
     *
     * @param loggable the loggable
     * @param logBuffer the destination buffer
     * @param logVersion the log version
     * @throws IllegalArgumentException if the log version is not supported
     */
    public static void writeToLog(final VersionedWriteLoggable loggable,
                                  final ByteBuffer logBuffer,
                                  final int logVersion) {
        checkCurrentVersion(loggable, logVersion);
        loggable.writeToLog(logBuffer);
    }

    /* Other methods */

    /**
     * Throw an appropriate {@link IllegalArgumentException} if a request has
     * been made for an operation on a log version that is older than the
     * loggable object's last format change.
     *
     * @param loggable the loggable
     * @param logVersion the requested log version
     * @throws IllegalArgumentException if the log version is older than the
     *         last format change
     */
    public static void checkCurrentVersion(
        final VersionedWriteLoggable loggable, final int logVersion) {

        final int lastFormatChange = loggable.getLastFormatChange();
        if (logVersion < lastFormatChange) {
            throw new IllegalArgumentException(
                "The requested log version, " + logVersion +
                ", is older than the last format change, " +
                lastFormatChange + ", for class " +
                loggable.getClass().getName());
        }
    }
}
