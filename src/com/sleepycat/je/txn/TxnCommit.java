/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.txn;

import java.nio.ByteBuffer;

import com.sleepycat.je.log.BasicVersionedWriteLoggable;
import com.sleepycat.je.log.Loggable;
import com.sleepycat.je.log.VersionedWriteLoggable;

/**
 * This class writes out a transaction commit or transaction end record.
 */
public class TxnCommit extends TxnEnd implements VersionedWriteLoggable {

    /**
     * The log version of the most recent format change for this loggable.
     *
     * @see #getLastFormatChange
     */
    private static final int LAST_FORMAT_CHANGE = 8;

    public TxnCommit(long id, long lastLsn, int masterId) {
        super(id, lastLsn, masterId);
    }

    /**
     * For constructing from the log.
     */
    public TxnCommit() {
    }

    /*
     * Log support
     */

    @Override
    protected String getTagName() {
        return "TxnCommit";
    }

    /**
     * @see VersionedWriteLoggable#getLastFormatChange
     */
    @Override
    public int getLastFormatChange() {
        return LAST_FORMAT_CHANGE;
    }

    /**
     * @see VersionedWriteLoggable#getLogSize(int)
     */
    @Override
    public int getLogSize(final int logVersion) {
        return BasicVersionedWriteLoggable.getLogSize(this, logVersion);
    }

    /**
     * @see VersionedWriteLoggable#writeToLog(ByteBuffer, int)
     */
    @Override
    public void writeToLog(final ByteBuffer logBuffer, final int logVersion) {
        BasicVersionedWriteLoggable.writeToLog(this, logBuffer, logVersion);
    }

    /**
     * @see Loggable#logicalEquals
     */
    @Override
    public boolean logicalEquals(Loggable other) {

        if (!(other instanceof TxnCommit))
            return false;

        TxnCommit otherCommit = (TxnCommit) other;

        return ((id == otherCommit.id) && 
                (repMasterNodeId == otherCommit.repMasterNodeId));
    }
}
