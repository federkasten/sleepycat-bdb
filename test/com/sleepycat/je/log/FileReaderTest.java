/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2005, 2010 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.log;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.ByteBuffer;

import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentLockedException;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.log.entry.LogEntry;
import com.sleepycat.je.util.DualTestCase;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.util.test.SharedTestUtils;

/**
 * Test edge cases for file reading.
 */
public class FileReaderTest extends DualTestCase {

    private final File envHome;

    public FileReaderTest() {
        envHome = SharedTestUtils.getTestDir();
    }

    /*
     * Check that we can handle the case when we are reading forward
     * with other than the LastFileReader, and the last file exists but is
     * 0 length. This case came up when a run of MemoryStress was killed off,
     * and we then attempted to read it with DbPrintLog.
     */
    @Test
    public void testEmptyExtraFile()
        throws Throwable {

        EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);
        Environment env = create(envHome, envConfig);

        try {
            /* Make an environment. */
            env.sync();

            /* Add an extra, 0 length file */
            EnvironmentImpl envImpl = DbInternal.getEnvironmentImpl(env);

            File newFile = new File(envHome, "00000001.jdb");
            newFile.createNewFile();

            INFileReader reader = new INFileReader(envImpl,
                                                   1000,
                                                   DbLsn.NULL_LSN,
                                                   DbLsn.NULL_LSN,
                                                   false,
                                                   false,
                                                   DbLsn.NULL_LSN,
                                                   DbLsn.NULL_LSN,
                                                   null);
            while (reader.readNextEntry()) {
            }

        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        } finally {
            close(env);
        }
    }

    /**
     * Check that we can read a log with various non-default parameters set.
     * (This test is currently only exercising one, je.log.checksumRead)
     * @throws DatabaseException
     * @throws EnvironmentLockedException
     */
    @Test
    public void testNonDefaultParams()
        throws Throwable {

        EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);

        /* Set non-default params. */
        envConfig.setConfigParam("je.log.checksumRead", "false");

        Environment env = create(envHome, envConfig);
        Database db = null;
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setTransactional(true);
        try {
            db = env.openDatabase(null, "foo", dbConfig);
            DatabaseEntry entry = new DatabaseEntry();
            for (int i = 0; i < 10; i++) {
                IntegerBinding.intToEntry(i, entry);
                assertEquals(OperationStatus.SUCCESS,
                             db.put(null, entry, entry));
            }

            env.sync();

            TestReader reader =
                new TestReader(DbInternal.getEnvironmentImpl(env));
            while (reader.readNextEntry()) {
            }
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        } finally {
            if (db != null) {
                db.close();
            }
            close(env);
        }
    }

    private static class TestReader extends FileReader {

        public TestReader(EnvironmentImpl envImpl)
                throws Exception {

            super(envImpl, 1024 /* readBufferSize*/, true /* forward */,
                  0L, null /* singleFileNumber */,
                  DbLsn.NULL_LSN /* endOfFileLsn */,
                  DbLsn.NULL_LSN /* finishLsn */);

        }

        @Override
        protected boolean processEntry(ByteBuffer entryBuffer)
                throws DatabaseException {

            LogEntryType type =
                LogEntryType.findType(currentEntryHeader.getType());
            LogEntry entry = type.getSharedLogEntry();
            entry.readEntry(envImpl, currentEntryHeader, entryBuffer);
            return true;
        }
    }
}
