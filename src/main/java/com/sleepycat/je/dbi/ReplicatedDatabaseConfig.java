/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.dbi;

import java.nio.ByteBuffer;
import java.util.Arrays;

import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.log.BasicVersionedWriteLoggable;
import com.sleepycat.je.log.LogUtils;
import com.sleepycat.je.log.Loggable;
import com.sleepycat.je.log.VersionedWriteLoggable;

/**
 * This class contains all fields of the database configuration which are
 * persistent. This class is logged as part of a nameLN so that databases can
 * be created on replica nodes with the correct configuration.
 */
public class ReplicatedDatabaseConfig extends BasicVersionedWriteLoggable {

    /**
     * The log version of the most recent format change for this loggable.
     *
     * @see #getLastFormatChange
     */
    public static final int LAST_FORMAT_CHANGE = 8;

    private byte flags;
    private int maxTreeEntriesPerNode;
    private byte[] btreeComparatorBytes = LogUtils.ZERO_LENGTH_BYTE_ARRAY;
    private byte[] duplicateComparatorBytes = LogUtils.ZERO_LENGTH_BYTE_ARRAY;
    private byte[][] triggerBytes = null;

    /** For reading */
    public ReplicatedDatabaseConfig() {
    }

    /** For writing */
    ReplicatedDatabaseConfig(byte flags,
                             int maxTreeEntriesPerNode,
                             byte[] btreeComparatorBytes,
                             byte[] duplicateComparatorBytes,
                             byte[][] triggerBytes) {

        this.flags = flags;
        this.maxTreeEntriesPerNode = maxTreeEntriesPerNode;

        if (btreeComparatorBytes != null) {
            this.btreeComparatorBytes = btreeComparatorBytes;
        }

        if (duplicateComparatorBytes != null) {
            this.duplicateComparatorBytes = duplicateComparatorBytes;
        }

        if (triggerBytes != null) {
            this.triggerBytes = triggerBytes;
        }
    }

    /**
     * Create a database config for use on the replica which contains
     * all the configuration options that were conveyed by way of this class.
     */
    public DatabaseConfig getReplicaConfig(EnvironmentImpl envImpl) {
        DatabaseConfig replicaConfig = new DatabaseConfig();
        replicaConfig.setTransactional(true);
        replicaConfig.setSortedDuplicates
            (DatabaseImpl.getSortedDuplicates(flags));

        /*
         * KeyPrefixing is set to true if dups are enabled, to account for the
         * upgrade scenario where the Master has not yet been upgraded but the
         * Replica has been.
         */
        replicaConfig.setKeyPrefixing(DatabaseImpl.getKeyPrefixing(flags) ||
                                      DatabaseImpl.getSortedDuplicates(flags));
        replicaConfig.setTemporary(DatabaseImpl.isTemporary(flags));
        replicaConfig.setReplicated(true);
        replicaConfig.setNodeMaxEntries(maxTreeEntriesPerNode);

        DatabaseImpl.ComparatorReader reader =
            new DatabaseImpl.ComparatorReader(btreeComparatorBytes,
                                              "Btree",
                                              envImpl.getClassLoader());
        if (reader.isClass()) {
            replicaConfig.setBtreeComparator(reader.getComparatorClass());
        } else {
            replicaConfig.setBtreeComparator(reader.getComparator());
        }

        reader = new DatabaseImpl.ComparatorReader(duplicateComparatorBytes,
                                                   "Duplicate",
                                                   envImpl.getClassLoader());
        if (reader.isClass()) {
            replicaConfig.setDuplicateComparator(reader.getComparatorClass());
        } else {
            replicaConfig.setDuplicateComparator(reader.getComparator());
        }

        replicaConfig.setTriggers(TriggerUtils.
                                  unmarshallTriggers(null, triggerBytes,
                                                     envImpl.getClassLoader()));

        return replicaConfig;
    }

    /** @see Loggable#getLogSize */
    @Override
    public int getLogSize() {
        return 1 + // flags, 1 byte
            LogUtils.getPackedIntLogSize(maxTreeEntriesPerNode) +
            LogUtils.getByteArrayLogSize(btreeComparatorBytes) +
            LogUtils.getByteArrayLogSize(duplicateComparatorBytes) +
            TriggerUtils.logSize(triggerBytes);
    }

    /** @see VersionedWriteLoggable#getLastFormatChange */
    @Override
    public int getLastFormatChange() {
        return LAST_FORMAT_CHANGE;
    }

    /** @see Loggable#writeToLog */
    @Override
    public void writeToLog(ByteBuffer logBuffer) {
        logBuffer.put(flags);
        LogUtils.writePackedInt(logBuffer, maxTreeEntriesPerNode);
        LogUtils.writeByteArray(logBuffer, btreeComparatorBytes);
        LogUtils.writeByteArray(logBuffer, duplicateComparatorBytes);
        TriggerUtils.writeTriggers(logBuffer, triggerBytes);
    }

    /** @see Loggable#readFromLog */
    @Override
    public void readFromLog(ByteBuffer itemBuffer, int entryVersion) {

        /*
         * ReplicatedDatabaseConfigs didn't exist before version 6 so they are
         * always packed.
         */
        flags = itemBuffer.get();
        maxTreeEntriesPerNode =
            LogUtils.readInt(itemBuffer, false/*unpacked*/);
        if (entryVersion < 8) {
            /* Discard maxDupTreeEntriesPerNode. */
            LogUtils.readInt(itemBuffer, false/*unpacked*/);
        }
        btreeComparatorBytes =
            LogUtils.readByteArray(itemBuffer, false/*unpacked*/);
        duplicateComparatorBytes =
            LogUtils.readByteArray(itemBuffer, false/*unpacked*/);
        triggerBytes = (entryVersion < 8) ?
                null :
                TriggerUtils.readTriggers(itemBuffer, entryVersion);
    }

    /** @see Loggable#dumpLog */
    @Override
    public void dumpLog(StringBuilder sb, boolean verbose) {
        sb.append("<config ");
        DatabaseImpl.dumpFlags(sb, verbose, flags);
        sb.append(" btcmpSet=\"").append(btreeComparatorBytes !=
                                         LogUtils.ZERO_LENGTH_BYTE_ARRAY);
        sb.append("\" dupcmpSet=\"").append(duplicateComparatorBytes !=
                                            LogUtils.ZERO_LENGTH_BYTE_ARRAY
                                            ).append("\"");
        TriggerUtils.dumpTriggers(sb, triggerBytes, null);
        sb.append(" />");
    }

    /** @see Loggable#getTransactionId */
    @Override
    public long getTransactionId() {
        return 0;
    }

    /** @see Loggable#logicalEquals */
    @Override
    public boolean logicalEquals(Loggable other) {
        if (!(other instanceof ReplicatedDatabaseConfig)) {
            return false;
        }

        ReplicatedDatabaseConfig otherConfig =
            (ReplicatedDatabaseConfig) other;

        if (flags != otherConfig.flags) {
            return false;
        }

        if (maxTreeEntriesPerNode !=
            otherConfig.maxTreeEntriesPerNode) {
            return false;
        }

        if (!Arrays.equals(btreeComparatorBytes,
                           otherConfig.btreeComparatorBytes)) {
            return false;
        }

        if (!Arrays.equals(duplicateComparatorBytes,
                           otherConfig.duplicateComparatorBytes)) {
            return false;
        }

        return true;
    }
}
