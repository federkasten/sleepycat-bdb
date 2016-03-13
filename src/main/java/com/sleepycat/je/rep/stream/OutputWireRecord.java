/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.stream;

import java.nio.ByteBuffer;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.log.LogEntryHeader;
import com.sleepycat.je.log.LogEntryType;
import com.sleepycat.je.log.LogItem;
import com.sleepycat.je.log.LogUtils;
import com.sleepycat.je.log.entry.LNLogEntry;
import com.sleepycat.je.log.entry.LogEntry;
import com.sleepycat.je.log.entry.ReplicableLogEntry;
import com.sleepycat.je.tree.NameLN;
import com.sleepycat.je.txn.TxnEnd;
import com.sleepycat.je.utilint.VLSN;

/**
 * Format for log entries sent across the wire for replication. In most
 * cases, the bytes are read directly from the log and never need to be
 * serialized into the backing object.
 *
 * Note that the ByteBuffer held within the OutputWireRecord has a limited
 * lifetime. Often it's just sliced, rather than copied from the underlying
 * buffer.
 */
public class OutputWireRecord extends WireRecord {

    protected final ByteBuffer entryBuffer;
    protected final EnvironmentImpl envImpl;

    /** A shared entry of the type specified by the header, or null */
    private ReplicableLogEntry sharedEntry = null;

    /** A log entry created from the data in the entry buffer, or null */
    private ReplicableLogEntry logEntry = null;

    /**
     * Make a OutputWireRecord from FileReader output for sending out.
     */
    OutputWireRecord(final EnvironmentImpl envImpl,
                     final LogEntryHeader header,
                     final ByteBuffer readerBuffer) {
        super(header);
        this.envImpl = envImpl;
        this.entryBuffer = readerBuffer.slice();
        this.entryBuffer.limit(header.getItemSize());
    }

    /**
     * Creates an OutputWireRecord from a log item. This constructor is used
     * when a Feeder can bypass access to the log because the log item is
     * available in the log item cache associated with the VLSNIndex.
     */
    OutputWireRecord(final EnvironmentImpl envImpl, final LogItem logItem) {
        super(logItem.getHeader());
        this.envImpl = envImpl;
        final ByteBuffer buffer = logItem.getBuffer();
        buffer.position(header.getSize());
        entryBuffer = buffer.slice();
        assert entryBuffer.limit() == header.getItemSize() :
            "Limit:" + entryBuffer.limit() + " size:" + header.getItemSize();
    }

    /* For unit test support. */
    OutputWireRecord(final EnvironmentImpl envImpl,
                     final InputWireRecord input) {
        super(input.header);
        this.envImpl = envImpl;
        final LogEntry entry = input.getLogEntry();
        this.entryBuffer = ByteBuffer.allocate(entry.getSize());
        entry.writeEntry(entryBuffer);
        entryBuffer.flip();
    }

    /**
     * Returns the shared replicable log entry associated with the log entry
     * header.
     */
    private synchronized ReplicableLogEntry getSharedEntry()
        throws DatabaseException {

        if (sharedEntry == null) {
            final LogEntryType entryType = getLogEntryType();
            if (!entryType.isReplicationPossible()) {
                throw EnvironmentFailureException.unexpectedState(
                    "Log entry type does not support replication: " + entryType);
            }
            sharedEntry = (ReplicableLogEntry) entryType.getSharedLogEntry();
        }
        return sharedEntry;
    }

    /**
     * Returns a log entry created from the data in the entry buffer.  Note
     * that the log entry will only be created once.
     */
    private synchronized ReplicableLogEntry instantiateEntry()
        throws DatabaseException {

        if (logEntry == null) {
            final LogEntry entry = instantiateEntry(envImpl, entryBuffer);
            if (!(entry instanceof ReplicableLogEntry)) {
                throw EnvironmentFailureException.unexpectedState(
                    "Log entry type does not support replication: " +
                    entry.getClass().getName());
            }
            logEntry = (ReplicableLogEntry) entry;
        }
        return logEntry;
    }

    /**
     * @return the log entry type for this record.
     */
    public byte getEntryType() {
        return header.getType();
    }

    /**
     * Used at syncup, when comparing records received from the feeder against
     * local records.
     *
     * @return true if this OutputWireRecord has the same logical contents as
     * the InputWireRecord.  The comparison will disregard portions of the
     * logEntry that may be different, such at timestamps on a Commit
     * entry. Must be called before the entryBuffer that backs this
     * OutputWireRecord is reused.
     * @throws DatabaseException
     */
    public boolean match(final InputWireRecord input)
        throws DatabaseException {

        /*
         * Ignore the log version check if the log versions on the feeder and
         * replica don't match. This would happen if the group is doing an
         * upgrade that requires a log version change.
         */
        if (!header.logicalEqualsIgnoreVersion(input.header)) {
            return false;
        }

        final LogEntry entry = instantiateEntry();
        return entry.logicalEquals(input.getLogEntry());
    }

    /**
     * For unit tests.
     * @return true if this OutputWireRecord has the same logical contents as
     * "other".
     * @throws DatabaseException
     */
    public boolean match(final OutputWireRecord otherRecord)
        throws DatabaseException {

        if (!header.logicalEqualsIgnoreVersion(otherRecord.header)) {
            return false;
        }

        final LogEntry entry = instantiateEntry();
        final LogEntry otherEntry =
            otherRecord.instantiateEntry(envImpl, otherRecord.entryBuffer);
        return entry.logicalEquals(otherEntry);
    }

    public VLSN getVLSN() {
        return header.getVLSN();
    }

    /**
     * Dump the contents.
     * @throws DatabaseException
     */
    public String dump()
        throws DatabaseException {

        final StringBuilder sb = new StringBuilder();
        header.dumpRep(sb);
        final LogEntry entry = instantiateEntry();
        entry.dumpRep(sb);
        return sb.toString();
    }

    @Override
    public String toString() {
        try {
           return dump();
        } catch (DatabaseException e) {
           e.printStackTrace();
           return "";
        }
    }

    /**
     * Returns the number of bytes needed to represent the message data for this
     * record for the specified log version.
     */
    int getWireSize(final int logVersion) {
        return 1 + 4 + 4 + VLSN.LOG_SIZE + getEntrySize(logVersion);
    }

    /**
     * Returns the number of bytes needed to represent the entry portion of the
     * message data for this record for the specified log version.
     */
    private int getEntrySize(final int logVersion) {
        if (requiresFormatChange(logVersion)) {
            return instantiateEntry().getSize(logVersion);
        }
        return header.getItemSize();
    }

    /**
     * Returns whether the format of the entry needs to be changed in order to
     * be read by a replica that only understands versions no later than {@code
     * logVersion}.
     */
    private boolean requiresFormatChange(final int logVersion) {
        return
            /* The requested version is older than the current version, */
            logVersion < LogEntryType.LOG_VERSION &&
            /* it is older than the entry version, */
            logVersion < header.getVersion() &&
            /* and it is older than the entry class's last format change */
            logVersion < (getSharedEntry().getLastFormatChange());
    }

    /**
     * Write the log header and entry associated with this instance to the
     * specified buffer using the format for the specified log version.
     *
     * @param messageBuffer the destination buffer
     * @param logVersion the log version of the format
     * @return whether the data format needed to be changed
     */
    boolean writeToWire(final ByteBuffer messageBuffer,
                        final int logVersion) {

        messageBuffer.put(header.getType());
        final boolean changeFormat = requiresFormatChange(logVersion);
        if (changeFormat) {
            final ReplicableLogEntry entry = instantiateEntry();
            LogUtils.writeInt(messageBuffer, logVersion);
            LogUtils.writeInt(messageBuffer, entry.getSize(logVersion));
            LogUtils.writeLong(messageBuffer, header.getVLSN().getSequence());
            entryBuffer.mark();
            entry.writeEntry(messageBuffer, logVersion);
        } else {
            LogUtils.writeInt(messageBuffer, header.getVersion());
            LogUtils.writeInt(messageBuffer, header.getItemSize());
            LogUtils.writeLong(messageBuffer, header.getVLSN().getSequence());
            entryBuffer.mark();
            messageBuffer.put(entryBuffer);
        }
        entryBuffer.reset();
        return changeFormat;
    }

    /*
     * Returns the transaction id associated with a commit log entry.
     * @return the transaction id, if it's a commit record, zero otherwise.
     */
    public long getCommitTxnId()
        throws DatabaseException {

        if (!LogEntryType.LOG_TXN_COMMIT.equalsType(header.getType())) {
            return 0;
        }

        final LogEntry commitEntry = instantiateEntry();
        return commitEntry.getTransactionId();
    }

    /*
     * Returns the timestamp associated with the entry that we sync'ed on.
     */
    public long getTimeStamp()
        throws DatabaseException {

        final LogEntry txnEndEntry = instantiateEntry();
        if (txnEndEntry instanceof TxnEnd) {
            return ((TxnEnd) txnEndEntry.getMainItem()).getTime().getTime();
        }
        return 0L;
    }

    /*
     * Unit test and assertion support: Transaction, database and node IDs in
     * the replication sequences are supposed to occupy the negative
     * numberspace.
     *
     * It seems a little fragile to test this here, using instanceof to decide
     * what to test. It would be cleaner to put this validity check as part of
     * the implementation of a log entry. But this is a HA related check, and
     * we want to keep the core code as independent as possible. The check is
     * here rather than in some other test code because it needs to access the
     * deserialized log entry. We don't want to provide a method which returns
     * a logEntry, because in general an OutputWireRecord should not
     * instantiate the log entry.
     *
     * @throws RuntimeException if there are any sequences that are not
     * negative.
     */
    public boolean verifyNegativeSequences(final String debugTag) {

        LogEntry entry = null;
        try {
            entry = instantiateEntry();
        } catch (DatabaseException e) {
            throw EnvironmentFailureException.unexpectedException(e);
        }

        if (entry.getTransactionId() >= 0) {
            throw EnvironmentFailureException.unexpectedState
                (debugTag + " txn id should be negative: " + entry);
        }

        if (entry instanceof LNLogEntry) {
            if (LogEntryType.LOG_NAMELN_TRANSACTIONAL.equalsType
                (getEntryType())) {
                final LNLogEntry<?> lnEntry = (LNLogEntry<?>) entry;
                lnEntry.postFetchInit(false /*isDupDb*/);
                final NameLN nameLN = (NameLN) lnEntry.getLN();
                if (nameLN.getId().getId() >= 0) {
                    throw EnvironmentFailureException.unexpectedState
                        (debugTag + " db id should be negative: " + entry);
                }
            } else {
                if (entry.getDbId().getId() >= 0) {
                    throw EnvironmentFailureException.unexpectedState
                        (debugTag + " db id should be negative: " + entry);
                }
            }
        }

        return true;
    }
}
