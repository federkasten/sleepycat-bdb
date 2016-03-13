/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.log.entry;

import java.nio.ByteBuffer;

import com.sleepycat.je.dbi.DatabaseId;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.log.LogEntryHeader;
import com.sleepycat.je.log.LogEntryType;
import com.sleepycat.je.log.Loggable;

/**
 * This class embodies log entries that have a single loggable item.
 * On disk, an entry contains:
 * <pre>
 *     the Loggable item
 * </pre>
 *
 * @param <T> the type of the Loggable item
 */
public class SingleItemEntry<T extends Loggable> extends BaseEntry<T>
        implements LogEntry {

    /*
     * Persistent fields in a SingleItemEntry.
     */
    private T item;

    /**
     * Construct a log entry for reading.
     */
    public static <T extends Loggable> SingleItemEntry<T> create(
        final Class<T> logClass) {

        return new SingleItemEntry<T>(logClass);
    }

    /**
     * Construct a log entry for reading.
     */
    SingleItemEntry(final Class<T> logClass) {
        super(logClass);
    }

    /**
     * Construct a log entry for writing.
     */
    public static <T extends Loggable> SingleItemEntry<T> create(
        final LogEntryType entryType, final T item) {

        return new SingleItemEntry<T>(entryType, item);
    }

    /**
     * Construct a log entry for writing.
     */
    public SingleItemEntry(final LogEntryType entryType, final T item) {
        setLogType(entryType);
        this.item = item;
    }

    /**
     * @see LogEntry#readEntry
     */
    @Override
    public void readEntry(EnvironmentImpl envImpl,
                          LogEntryHeader header,
                          ByteBuffer entryBuffer) {

        item = newInstanceOfType();
        item.readFromLog(entryBuffer, header.getVersion());
    }

    /**
     * @see LogEntry#dumpEntry
     */
    @Override
    public StringBuilder dumpEntry(final StringBuilder sb,
                                   final boolean verbose) {
        item.dumpLog(sb, verbose);
        return sb;
    }

    /**
     * @see LogEntry#dumpRep
     */
    @Override
    public void dumpRep(@SuppressWarnings("unused") StringBuilder sb) {
    }

    /**
     * @see LogEntry#getMainItem
     */
    @Override
    public T getMainItem() {
        return item;
    }

    /**
     * @see LogEntry#getTransactionId
     */
    @Override
    public long getTransactionId() {
        return item.getTransactionId();
    }

    @Override
    public DatabaseId getDbId() {
        return null;
    }

    /*
     * Writing support
     */

    @Override
    public int getSize() {
        return item.getLogSize();
    }

    /**
     * @see LogEntry#writeEntry
     */
    @Override
    public void writeEntry(final ByteBuffer destBuffer) {
        item.writeToLog(destBuffer);
    }

    /**
     * @see LogEntry#logicalEquals
     */
    @Override
    public boolean logicalEquals(final LogEntry other) {
        return item.logicalEquals((Loggable) other.getMainItem());
    }
}
