/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.log.entry;

import java.nio.ByteBuffer;

import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.dbi.DupKeyData;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.log.LogEntryHeader;
import com.sleepycat.je.log.LogUtils;
import com.sleepycat.je.tree.Key;
import com.sleepycat.je.tree.LN;

/**
 * DupDeletedLNEntry encapsulates a deleted dupe LN entry. This contains all
 * the regular transactional LN log entry fields and an extra key, which is the
 * nulled out data field of the LN (which becomes the key in the duplicate
 * tree.
 *
 * WARNING: Obsolete in version 8, only used by some log readers.
 *
 * TODO Move to dupConvert package, after testing is complete.
 */
public class DeletedDupLNLogEntry extends LNLogEntry<LN> {

    /*
     * Deleted duplicate LN must log an entra key in their log entries,
     * because the data field that is the "key" in a dup tree has been
     * nulled out because the LN is deleted.
     */
    private byte[] dataAsKey;

    /**
     * Constructor to read an entry.
     */
    public DeletedDupLNLogEntry() {
        super(com.sleepycat.je.tree.LN.class);
    }

    @Override
    byte[] combineDupKeyData() {
        return DupKeyData.combine(getKey(), dataAsKey);
    }

    /**
     * Extends its super class to read in the extra dup key.
     * @see LNLogEntry#readEntry
     */
    @Override
    public void readEntry(EnvironmentImpl envImpl,
                          LogEntryHeader header,
                          ByteBuffer entryBuffer) {

        readBaseLNEntry(envImpl, header, entryBuffer, 
                        false /*keyIsLastSerializedField*/);

        /* Key */
        int logVersion = header.getVersion();
        dataAsKey = LogUtils.readByteArray(entryBuffer, (logVersion < 6));
    }

    /**
     * Extends super class to dump out extra key.
     * @see LNLogEntry#dumpEntry
     */
    @Override
    public StringBuilder dumpEntry(StringBuilder sb, boolean verbose) {
        super.dumpEntry(sb, verbose);
        sb.append(Key.dumpString(dataAsKey, 0));
        return sb;
    }

    /*
     * Writing support
     */

    /**
     * Extend super class to add in extra key.
     * @see LNLogEntry#getSize
     */
    @Override
    public int getSize() {
        throw EnvironmentFailureException.unexpectedState();
    }

    /**
     * @see LogEntry#writeEntry
     */
    @Override
    public void writeEntry(ByteBuffer destBuffer) {
        throw EnvironmentFailureException.unexpectedState();
    }

    /**
     * @see ReplicableLogEntry#writeEntry(ByteBuffer, int)
     */
    @Override
    public void writeEntry(@SuppressWarnings("unused") ByteBuffer destBuffer,
                           @SuppressWarnings("unused") int logVersion) {
        throw EnvironmentFailureException.unexpectedState();
    }
}
