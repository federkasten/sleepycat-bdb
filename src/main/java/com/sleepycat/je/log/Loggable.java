/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * [This line intentionally left blank.]
 * EOF/
 *
 */

package com.sleepycat.je.log;

import java.nio.ByteBuffer;

/**
 * A class that implements Loggable knows how to read and write itself into
 * a ByteBuffer in a format suitable for the JE log or JE replication
 * messages.
 *
 * <p>Classes that implement {@code Loggable} and are included in replication
 * data should implement {@code VersionedWriteLoggable}.
 */
public interface Loggable {

    /*
     * Writing to a byte buffer
     */

    /**
     * @return number of bytes used to store this object.
     */
    public int getLogSize();

    /**
     * Serialize this object into the buffer.
     * @param logBuffer is the destination buffer
     */
    public void writeToLog(ByteBuffer logBuffer);

    /*
     *  Reading from a byte buffer
     */

    /**
     * Initialize this object from the data in itemBuf.
     * @param itemBuffer the source buffer
     * @param entryVersion the log version of the data
     */
    public void readFromLog(ByteBuffer itemBuffer, int entryVersion);

    /**
     * Write the object into the string buffer for log dumping. Each object
     * should be dumped without indentation or new lines and should be valid
     * XML.
     * @param sb destination string buffer
     * @param verbose if true, dump the full, verbose version
     */
    public void dumpLog(StringBuilder sb, boolean verbose);

    /**
     * @return the transaction id embedded within this loggable object. Objects
     * that have no transaction id should return 0.
     */
    public long getTransactionId();

    /**
     * @return true if these two loggable items are logically the same.
     * Used for replication testing.
     */
    public boolean logicalEquals(Loggable other);
}
