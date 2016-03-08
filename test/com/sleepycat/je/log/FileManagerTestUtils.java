/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.log;

import java.io.IOException;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.dbi.EnvironmentImpl;

public class FileManagerTestUtils {
    public static void createLogFile(FileManager fileManager,
                                         EnvironmentImpl envImpl,
                                         long logFileSize)
        throws DatabaseException, IOException {

        LogBuffer logBuffer = new LogBuffer(50, envImpl);
        logBuffer.getDataBuffer().flip();
        fileManager.bumpLsn(logFileSize - FileManager.firstLogEntryOffset());
        logBuffer.registerLsn(fileManager.getLastUsedLsn());
        fileManager.writeLogBuffer(logBuffer, true);
        fileManager.syncLogEndAndFinishFile();
    }
}
