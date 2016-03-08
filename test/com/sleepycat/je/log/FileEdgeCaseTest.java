/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.log;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import org.junit.After;
import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.dbi.EnvironmentFailureReason;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

public class FileEdgeCaseTest extends TestBase {

    private final File envHome;
    private Environment env;
    private EnvironmentConfig envConfig;
    private String firstFile;

    public FileEdgeCaseTest() {
        envHome = SharedTestUtils.getTestDir();
    }

    @After
    public void tearDown() {

        /*
         * Close down environments in case the unit test failed so that the log
         * files can be removed.
         */
        try {
            if (env != null) {
                env.close();
                env = null;
            }
        } catch (DatabaseException e) {
            e.printStackTrace();
            // ok, the test closed it
        }
    }

    /**
     * SR #15133
     * Create a JE environment with a single log file and a checksum
     * exception in the second entry in the log file.
     *
     * When an application attempts to open this JE environment, JE truncates
     * the log file at the point before the bad checksum, because it assumes
     * that bad entries at the end of the log are the result of incompletely
     * flushed writes from the last environment use.  However, the truncated
     * log doesn't have a valid environment root, so JE complains and asks the
     * application to move aside the existing log file (via the exception
     * message). The resulting environment has a single log file, with
     * a single valid entry, which is the file header.
     *
     * Any subsequent attempts to open the environment should also fail at the
     * same point. In the error case reported by this SR, we didn't handle this
     * single log file/single file header case right, and subsequent opens
     * first truncated before the file header, leaving a 0 length log, and
     * then proceeded to write error trace messages into the log. This
     * resulted in a log file with no file header, (but with trace messages)
     * and any following opens got unpredictable errors like
     * ClassCastExceptions and BufferUnderflows.
     *
     * The correct situation is to continue to get the same exception.
     */
    @Test
    public void testPostChecksumError()
        throws IOException, DatabaseException {

        EnvironmentConfig config = new EnvironmentConfig();
        config.setAllowCreate(true);
        env = new Environment(envHome, config);

        EnvironmentImpl envImpl = DbInternal.getEnvironmentImpl(env);
        FileManager fm = envImpl.getFileManager();
        firstFile = fm.getFullFileName(0, FileManager.JE_SUFFIX);

        env.close();
        env = null;

        /* Intentionally corrupt the second entry. */
        corruptSecondEntry();

        /*
         * Next attempt to open the environment should fail with a
         * EnvironmentFailureException
         */
        try {
            env = new Environment(envHome, config);
        } catch (EnvironmentFailureException expected) {
            assertSame(EnvironmentFailureReason.LOG_INTEGRITY,
                       expected.getReason());
        }

        /*
         * Next attempt to open the environment should fail with a
         * EnvironmentFailureException
         */
        try {
            env = new Environment(envHome, config);
        } catch (EnvironmentFailureException expected) {
            assertSame(EnvironmentFailureReason.LOG_INTEGRITY,
                       expected.getReason());
        }
    }

    /* 
     * [#18307]
     * Suppose we have LSN 1000, and the log entry there has a checksum 
     * exception.
     *
     * Case 1. if we manage to read past LSN 1000, but then hit a second 
     *         checksum exception, return false and truncate the log at the 
     *         first exception.
     * Case 2. if we manage to read past LSN 1000, and do not see any checksum 
     *         exceptions, and do not see any commits, return false and 
     *         truncate the log.
     * Case 3. if we manage to read past LSN 1000, and do not see any checksum 
     *         exceptions, but do see a txn commit, return true and throw 
     *         EnvironmentFailureException.
     */
    @Test
    public void testFindCommittedTxn()
        throws IOException, DatabaseException {

        createDB();

        /* 
         * Case 2: Intentionally pollute the entry checksum after all committed 
         * txns, so no committed txn will be found.
         * 
         * There are 3 log entries which are committed txns: LSN 0x153, 
         * LSN 0x409 and LSN 0x48e. So if we want to pollute the checksum after 
         * all the committed txns, we have to pollute the log entry after 
         * LSN 0x48e.
         */    
        polluteEntryChecksum(1316);

        /* 
         * When doing recovery, no committed txn will be found. So the recovery 
         * process just truncate the log file at the bad log point.
         */
        try {
            env = new Environment(envHome, envConfig);  
            env.close();
            env = null;
        } catch (Exception e) {
            fail("Caught exception while recovering an Environment.");
        }

        /* Case 3: Intentionally pollute the entry checksum before the 
         * committed txn LSN 0x409. 
         *
         * When the reader meets the first checksum error, it will step forward
         * to look for the committed txn. After finding the committed txn, 
         * EnvironmentFailureException will be thrown, the recovery process
         * will be stopped.
         */
        polluteEntryChecksum(395);

        /*
         * Next attempt to open the environment should fail with a
         * EnvironmentFailureException.
         */
        try {
        
            /* 
             * When doing recovery, one committed txn will be found. So
             * EnvironmentFailureException will be thrown.
             */
            env = new Environment(envHome, envConfig);
        } catch (EnvironmentFailureException expected) {
            assertSame(EnvironmentFailureReason.FOUND_COMMITTED_TXN,
                       expected.getReason());
        }   
        
        /* 
         * Case 1: Intentionally pollute two entries' checksums before the 
         * committed txn LSN 0x409. 
         *  
         * When the reader meets the first checksum error, it will step forward
         * to look for the committed txn. Before finding any committed txn, if 
         * the reader meets another checksum error, it will stop the search, 
         * and just truncate the log file at the first checksum error spot.
         */
        polluteEntryChecksum(395);
        polluteEntryChecksum(752);
        
        /* 
         * When doing recovery, no committed txn will be found. So the recovery 
         * process just truncate the log file at the corruptted log entry.
         */
        try {
            env = new Environment(envHome, envConfig);  
            env.close();
            env = null;
        } catch (Exception e) {
            fail("Caught exception while recovering an Environment.");
        }
    }

    private void createDB() {
        /* Initiate JE environment and database. */
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);
        envConfig.setConfigParam
            (EnvironmentConfig.HALT_ON_COMMIT_AFTER_CHECKSUMEXCEPTION, "true");
        env = new Environment(envHome, envConfig);
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setTransactional(true);
        Database db = env.openDatabase(null, "testDB", dbConfig);
        
        /* Insert one record into db by transactional. */
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();
        IntegerBinding.intToEntry(0, key);
        IntegerBinding.intToEntry(0, data);
        Transaction txn = env.beginTransaction(null, null);
        db.put(txn, key, data); 
        txn.commit();

        EnvironmentImpl envImpl = DbInternal.getEnvironmentImpl(env);
        FileManager fm = envImpl.getFileManager();
        firstFile = fm.getFullFileName(0, FileManager.JE_SUFFIX);

        db.close();
        db = null;
        env.close();
        env = null;
    }

    /**
     * Write junk into the second log entry, after the file header.
     */
    private void corruptSecondEntry()
        throws IOException {

        RandomAccessFile file =
            new RandomAccessFile(firstFile,
                                 FileManager.FileMode.
                                 READWRITE_MODE.getModeValue());

        try {
            byte[] junk = new byte[20];
            file.seek(FileManager.firstLogEntryOffset());
            file.write(junk);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            file.close();
        }
    }
    
    /**
     * Pollute a specified log entry's checksum.
     */
    private void polluteEntryChecksum(int entryOffset)
        throws IOException {

        RandomAccessFile file =
            new RandomAccessFile(firstFile,
                                 FileManager.FileMode.
                                 READWRITE_MODE.getModeValue());
        try {

            /* 
             * We just want to pollute the checksum bytes, so the junk has 4 
             * bytes.
             */
            byte[] junk = new byte[4];
            file.seek(entryOffset);
            file.write(junk);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            file.close();
        }
    }
}
