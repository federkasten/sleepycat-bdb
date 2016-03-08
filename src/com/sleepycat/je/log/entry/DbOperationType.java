/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.log.entry;

import java.nio.ByteBuffer;

import com.sleepycat.je.log.BasicVersionedWriteLoggable;
import com.sleepycat.je.log.Loggable;
import com.sleepycat.je.log.VersionedWriteLoggable;

/**
 * DbOperationType is a persistent enum used in NameLNLogEntries. It supports
 * replication of database operations by documenting the type of api operation
 * which instigated the logging of a NameLN.
 */
public enum DbOperationType implements VersionedWriteLoggable {

    NONE((byte) 0),
    CREATE((byte) 1),
    REMOVE((byte) 2),
    TRUNCATE((byte) 3),
    RENAME((byte) 4),
    UPDATE_CONFIG((byte) 5);

    /**
     * The log version of the most recent format change for this loggable.
     *
     * @see #getLastFormatChange
     */
    public static final int LAST_FORMAT_CHANGE = 8;

    private byte value;

    private DbOperationType(byte value) {
        this.value = value;
    }

    public static DbOperationType readTypeFromLog(final ByteBuffer entryBuffer,
                                                  @SuppressWarnings("unused")
                                                  int entryVersion) {
        byte opVal = entryBuffer.get();
        switch (opVal) {
        case 1:
            return CREATE;

        case 2:
            return REMOVE;

        case 3:
            return TRUNCATE;

        case 4:
            return RENAME;

        case 5:
            return UPDATE_CONFIG;

        case 0:
        default:
            return NONE;

        }
    }

    /** @see Loggable#getLogSize */
    @Override
    public int getLogSize() {
        return 1;
    }

    /** @see VersionedWriteLoggable#getLastFormatChange */
    @Override
    public int getLastFormatChange() {
        return LAST_FORMAT_CHANGE;
    }

    /** @see VersionedWriteLoggable#getLogSize(int) */
    @Override
    public int getLogSize(final int logVersion) {
        return BasicVersionedWriteLoggable.getLogSize(this, logVersion);
    }

    /** @see VersionedWriteLoggable#writeToLog(ByteBuffer, int) */
    @Override
    public void writeToLog(final ByteBuffer logBuffer, final int logVersion) {
        BasicVersionedWriteLoggable.writeToLog(this, logBuffer, logVersion);
    }

    /** @see Loggable#writeToLog */
    @Override
    public void writeToLog(ByteBuffer logBuffer) {
        logBuffer.put(value);
    }

    /** @see Loggable#readFromLog */
    @Override
    public void readFromLog(ByteBuffer itemBuffer, int entryVersion) {
        value = itemBuffer.get();
    }

    /** @see Loggable#dumpLog */
    @Override
    public void dumpLog(StringBuilder sb, boolean verbose) {
        sb.append("<DbOp val=\"").append(this).append("\"/>");
    }

    /** @see Loggable#getTransactionId */
    @Override
    public long getTransactionId() {
        return 0;
    }

    /** @see Loggable#logicalEquals */
    @Override
    public boolean logicalEquals(Loggable other) {
        if (!(other instanceof DbOperationType))
            return false;

        return value == ((DbOperationType) other).value;
    }

    /**
     * Return true if this database operation type needs to write
     * DatabaseConfig.
     */
    public static boolean isWriteConfigType(DbOperationType opType) {
        return (opType == CREATE || opType == UPDATE_CONFIG);
    }
}
