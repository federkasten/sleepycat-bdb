/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.ByteBuffer;

import org.junit.After;
import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentMutableConfig;
import com.sleepycat.je.EnvironmentStats;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationFailureException;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.RunRecoveryException;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.cleaner.UtilizationProfile;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;
import com.sleepycat.utilint.StringUtils;

public class IOExceptionTest extends TestBase {

    private Environment env;
    private Database db;
    private final File envHome;

    public IOExceptionTest() {
        envHome = SharedTestUtils.getTestDir();
    }

    @After
    public void tearDown()
        throws DatabaseException {

        FileManager.IO_EXCEPTION_TESTING_ON_WRITE = false;
        if (db != null) {
            db.close();
        }

        if (env != null) {
            env.close();
        }
    }

    @Test
    public void testRunRecoveryExceptionOnWrite() {
        try {
            createDatabase(200000, 0, false);

            final int N_RECS = 25;

            CheckpointConfig chkConf = new CheckpointConfig();
            chkConf.setForce(true);
            Transaction txn = env.beginTransaction(null, null);
            int keyInt = 0;
            FileManager.IO_EXCEPTION_TESTING_ON_WRITE = false;
            for (int i = 0; i < N_RECS; i++) {
                String keyStr = Integer.toString(keyInt);
                DatabaseEntry key =
                    new DatabaseEntry(StringUtils.toUTF8(keyStr));
                DatabaseEntry data =
                    new DatabaseEntry(StringUtils.toUTF8(("d" + keyStr)));
                if (i == (N_RECS - 1)) {
                    FileManager.THROW_RRE_FOR_UNIT_TESTS = true;
                    FileManager.IO_EXCEPTION_TESTING_ON_WRITE = true;
                }
                try {
                    assertTrue(db.put(txn, key, data) ==
                               OperationStatus.SUCCESS);
                } catch (DatabaseException DE) {
                    fail("unexpected DatabaseException");
                    break;
                }
            }

            try {
                txn.commit();
                fail("expected DatabaseException");
            } catch (RunRecoveryException DE) {
            }
            forceCloseEnvOnly();

            FileManager.IO_EXCEPTION_TESTING_ON_WRITE = false;
            FileManager.THROW_RRE_FOR_UNIT_TESTS = false;
            db = null;
            env = null;
        } catch (Throwable E) {
            E.printStackTrace();
        }
    }

    @Test
    public void testIOExceptionNoRecovery()
        throws Throwable {

        doIOExceptionTest(false);
    }

    @Test
    public void testIOExceptionWithRecovery()
        throws Throwable {

        doIOExceptionTest(true);
    }

    @Test
    public void testEviction() {
        try {
            createDatabase(200000, 0, true);

            final int N_RECS = 25;

            CheckpointConfig chkConf = new CheckpointConfig();
            chkConf.setForce(true);
            Transaction txn = env.beginTransaction(null, null);
            int keyInt = 0;
            FileManager.IO_EXCEPTION_TESTING_ON_WRITE = true;
            for (int i = 0; i < N_RECS; i++) {
                String keyStr = Integer.toString(keyInt);
                DatabaseEntry key =
                    new DatabaseEntry(StringUtils.toUTF8(keyStr));
                DatabaseEntry data =
                    new DatabaseEntry(StringUtils.toUTF8(("d" + keyStr)));
                try {
                    assertTrue(db.put(txn, key, data) ==
                               OperationStatus.SUCCESS);
                } catch (DatabaseException DE) {
                    fail("unexpected DatabaseException");
                    break;
                }
            }

            try {
                env.checkpoint(chkConf);
                fail("expected DatabaseException");
            } catch (DatabaseException DE) {
            }

            EnvironmentStats stats = env.getStats(null);
            assertTrue((stats.getNFullINFlush() +
                        stats.getNFullBINFlush()) > 0);

            /* Read back the data and make sure it all looks ok. */
            for (int i = 0; i < N_RECS; i++) {
                String keyStr = Integer.toString(keyInt);
                DatabaseEntry key =
                    new DatabaseEntry(StringUtils.toUTF8(keyStr));
                DatabaseEntry data = new DatabaseEntry();
                try {
                    assertTrue(db.get(txn, key, data, null) ==
                               OperationStatus.SUCCESS);
                    assertEquals(StringUtils.fromUTF8(data.getData()),
                                 "d" + keyStr);
                } catch (DatabaseException DE) {
                    fail("unexpected DatabaseException");
                    break;
                }
            }

            /*
             * Now we have some IN's in the log buffer and there have been
             * IOExceptions that will later force rewriting that buffer.
             */
            FileManager.IO_EXCEPTION_TESTING_ON_WRITE = false;
            try {
                txn.commit();
            } catch (DatabaseException DE) {
                fail("unexpected DatabaseException");
            }
        } catch (Exception E) {
            E.printStackTrace();
        }
    }

    /*
     * Test for SR 13898.  Write out some records with
     * IO_EXCEPTION_TESTING_ON_WRITE true thereby forcing some commits to be
     * rewritten as aborts.  Ensure that the checksums are correct on those
     * rewritten records by reading them back with a file reader.
     */
    @Test
    public void testIOExceptionReadBack()
        throws Exception {

        createDatabase(100000, 1000, true);

        /*
         * Turn off daemons so we can check the size of the log
         * deterministically.
         */
        EnvironmentMutableConfig newConfig = new EnvironmentMutableConfig();
        newConfig.setConfigParam("je.env.runCheckpointer", "false");
        newConfig.setConfigParam("je.env.runCleaner", "false");
        env.setMutableConfig(newConfig);

        final int N_RECS = 25;

        /* Intentionally corrupt the transaction commit record. */
        CheckpointConfig chkConf = new CheckpointConfig();
        chkConf.setForce(true);
        Transaction txn = env.beginTransaction(null, null);
        for (int i = 0; i < N_RECS; i++) {
            String keyStr = Integer.toString(i);
            DatabaseEntry key =
                new DatabaseEntry(StringUtils.toUTF8(keyStr));
            DatabaseEntry data =
                new DatabaseEntry(new byte[100]);
            try {
                assertTrue(db.put(txn, key, data) ==
                           OperationStatus.SUCCESS);
            } catch (DatabaseException DE) {
                fail("unexpected DatabaseException");
                break;
            }
            try {
                FileManager.IO_EXCEPTION_TESTING_ON_WRITE = true;
                txn.commit();
                fail("expected DatabaseException");
            } catch (DatabaseException DE) {
            }
            FileManager.IO_EXCEPTION_TESTING_ON_WRITE = false;
            txn = env.beginTransaction(null, null);
        }

        FileManager.IO_EXCEPTION_TESTING_ON_WRITE = false;

        try {
            txn.commit();
        } catch (DatabaseException DE) {
            fail("unexpected DatabaseException");
        }

        /* Flush the corrupted records to disk. */
        try {
            env.checkpoint(chkConf);
        } catch (DatabaseException DE) {
            DE.printStackTrace();
            fail("unexpected DatabaseException");
        }

        EnvironmentStats stats = env.getStats(null);
        assertTrue((stats.getNFullINFlush() +
                    stats.getNFullBINFlush()) > 0);

        /*
         * Figure out where the log starts and ends, and make a local
         * FileReader class to mimic reading the logs. The only action we need
         * is to run checksums on the entries.
         */
        EnvironmentImpl envImpl = DbInternal.getEnvironmentImpl(env);
        long lastLsn = envImpl.getFileManager().getLastUsedLsn();
        Long firstFile = envImpl.getFileManager().getFirstFileNum();
        long firstLsn = DbLsn.makeLsn(firstFile, 0);

        FileReader reader = new FileReader
            (envImpl,
             4096,              // readBufferSize
             true,              // forward
             firstLsn,
             null,              // singleFileNumber
             lastLsn,           // end of file lsn
             DbLsn.NULL_LSN) {  // finishLsn

                @Override
                protected boolean processEntry(ByteBuffer entryBuffer) {
                    entryBuffer.position(entryBuffer.position() +
                                         currentEntryHeader.getItemSize());
                    return true;
                }
            };

        /* Read the logs, checking checksums. */
        while (reader.readNextEntry()) {
        }

        /* Check that the reader reads all the way to last entry. */
        assertEquals("last=" + DbLsn.getNoFormatString(lastLsn) +
                     " readerlast=" +
                     DbLsn.getNoFormatString(reader.getLastLsn()),
                     lastLsn,
                     reader.getLastLsn());
    }

    @Test
    public void testLogBufferOverflowAbortNoDupes() {
        doLogBufferOverflowTest(false, false);
    }

    @Test
    public void testLogBufferOverflowCommitNoDupes() {
        doLogBufferOverflowTest(true, false);
    }

    @Test
    public void testLogBufferOverflowAbortDupes() {
        doLogBufferOverflowTest(false, true);
    }

    @Test
    public void testLogBufferOverflowCommitDupes() {
        doLogBufferOverflowTest(true, true);
    }

    private void doLogBufferOverflowTest(boolean abort, boolean dupes) {
        try {
            EnvironmentConfig envConfig = TestUtils.initEnvConfig();
            envConfig.setTransactional(true);
            envConfig.setAllowCreate(true);
            envConfig.setCacheSize(100000);
            env = new Environment(envHome, envConfig);

            String databaseName = "ioexceptiondb";
            DatabaseConfig dbConfig = new DatabaseConfig();
            dbConfig.setAllowCreate(true);
            dbConfig.setSortedDuplicates(true);
            dbConfig.setTransactional(true);
            db = env.openDatabase(null, databaseName, dbConfig);

            Transaction txn = env.beginTransaction(null, null);
            DatabaseEntry oneKey =
                (dupes ?
                 new DatabaseEntry(StringUtils.toUTF8("2")) :
                 new DatabaseEntry(StringUtils.toUTF8("1")));
            DatabaseEntry oneData =
                new DatabaseEntry(new byte[10]);
            DatabaseEntry twoKey =
                new DatabaseEntry(StringUtils.toUTF8("2"));
            DatabaseEntry twoData =
                new DatabaseEntry(new byte[100000]);
            if (dupes) {
                DatabaseEntry temp = oneKey;
                oneKey = oneData;
                oneData = temp;
                temp = twoKey;
                twoKey = twoData;
                twoData = temp;
            }

            try {
                assertTrue(db.put(txn, oneKey, oneData) ==
                           OperationStatus.SUCCESS);
                db.put(txn, twoKey, twoData);
            } catch (DatabaseException DE) {
                fail("unexpected DatabaseException");
            }

            /* Read back the data and make sure it all looks ok. */
            try {
                assertTrue(db.get(txn, oneKey, oneData, null) ==
                           OperationStatus.SUCCESS);
                assertTrue(oneData.getData().length == (dupes ? 1 : 10));
            } catch (DatabaseException DE) {
                fail("unexpected DatabaseException");
            }

            try {
                assertTrue(db.get(txn, twoKey, twoData, null) ==
                           OperationStatus.SUCCESS);
            } catch (DatabaseException DE) {
                fail("unexpected DatabaseException");
            }

            try {
                if (abort) {
                    txn.abort();
                } else {
                    txn.commit();
                }
            } catch (DatabaseException DE) {
                fail("unexpected DatabaseException");
            }

            /* Read back the data and make sure it all looks ok. */
            try {
                assertTrue(db.get(null, oneKey, oneData, null) ==
                           (abort ?
                            OperationStatus.NOTFOUND :
                            OperationStatus.SUCCESS));
                assertTrue(oneData.getData().length == (dupes ? 1 : 10));
            } catch (DatabaseException DE) {
                fail("unexpected DatabaseException");
            }

            try {
                assertTrue(db.get(null, twoKey, twoData, null) ==
                           (abort ?
                            OperationStatus.NOTFOUND :
                            OperationStatus.SUCCESS));
            } catch (DatabaseException DE) {
                fail("unexpected DatabaseException");
            }

        } catch (Exception E) {
            E.printStackTrace();
        }
    }

    @Test
    public void testPutTransactionalWithIOException() {
        try {
            createDatabase(100000, 0, true);

            Transaction txn = env.beginTransaction(null, null);
            int keyInt = 0;
            String keyStr;
            FileManager.IO_EXCEPTION_TESTING_ON_WRITE = true;

            /* Fill up the buffer until we see an IOException. */
            while (true) {
                keyStr = Integer.toString(++keyInt);
                DatabaseEntry key =
                    new DatabaseEntry(StringUtils.toUTF8(keyStr));
                DatabaseEntry data =
                    new DatabaseEntry(StringUtils.toUTF8(("d" + keyStr)));
                try {
                    assertTrue(db.put(txn, key, data) ==
                               OperationStatus.SUCCESS);
                } catch (DatabaseException DE) {
                    break;
                }
            }

            /* Buffer still hasn't been written.  This should also fail. */
            try {
                db.put(txn,
                       new DatabaseEntry(StringUtils.toUTF8("shouldFail")),
                       new DatabaseEntry(StringUtils.toUTF8("shouldFailD")));
                fail("expected DatabaseException");
            } catch (DatabaseException e) {
                /* Expected. */
            }
            FileManager.IO_EXCEPTION_TESTING_ON_WRITE = false;

            /* Fails because the txn is abort-only. */
            try {
                db.put(txn,
                       new DatabaseEntry(StringUtils.toUTF8("shouldAlsoFail")),
                       new DatabaseEntry(StringUtils.toUTF8("shouldAlsoFailD")));
                fail("expected DatabaseException");
            } catch (OperationFailureException e) {
                /* Expected. */
            }
            txn.abort();

            /* Txn aborted.  None of the entries should be found. */
            DatabaseEntry data = new DatabaseEntry();
            assertTrue(db.get(null,
                              new DatabaseEntry
                              (StringUtils.toUTF8("shouldAlsoFail")),
                              data,
                              null) == OperationStatus.NOTFOUND);

            assertTrue(db.get(null,
                              new DatabaseEntry
                              (StringUtils.toUTF8("shouldFail")),
                              data,
                              null) == OperationStatus.NOTFOUND);

            assertTrue(db.get(null,
                              new DatabaseEntry
                              (StringUtils.toUTF8("shouldFail")),
                              data,
                              null) == OperationStatus.NOTFOUND);

            assertTrue(db.get(null,
                              new DatabaseEntry(StringUtils.toUTF8(keyStr)),
                              data,
                              null) == OperationStatus.NOTFOUND);

            for (int i = --keyInt; i > 0; i--) {
                keyStr = Integer.toString(i);
                assertTrue(db.get(null,
                                  new DatabaseEntry(StringUtils.toUTF8(keyStr)),
                                  data,
                                  null) == OperationStatus.NOTFOUND);
            }

        } catch (Throwable T) {
            T.printStackTrace();
        }
    }

    @Test
    public void testIOExceptionDuringFileFlippingWrite() {
        doIOExceptionDuringFileFlippingWrite(8, 33, 2);
    }

    private void doIOExceptionDuringFileFlippingWrite(int numIterations,
                                                      int exceptionStartWrite,
                                                      int exceptionWriteCount) {
        try {
            EnvironmentConfig envConfig = new EnvironmentConfig();
            DbInternal.disableParameterValidation(envConfig);
            envConfig.setTransactional(true);
            envConfig.setAllowCreate(true);
            envConfig.setConfigParam("je.log.fileMax", "1000");
            envConfig.setConfigParam("je.log.bufferSize", "1025");
            envConfig.setConfigParam("je.env.runCheckpointer", "false");
            envConfig.setConfigParam("je.env.runCleaner", "false");
            env = new Environment(envHome, envConfig);

            EnvironmentImpl envImpl = DbInternal.getEnvironmentImpl(env);
            DatabaseConfig dbConfig = new DatabaseConfig();
            dbConfig.setTransactional(true);
            dbConfig.setAllowCreate(true);
            db = env.openDatabase(null, "foo", dbConfig);

            /*
             * Put one record into the database so it gets populated w/INs and
             * LNs, and we can fake out the RMW commits used below.
             */
            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry data = new DatabaseEntry();
            IntegerBinding.intToEntry(5, key);
            IntegerBinding.intToEntry(5, data);
            db.put(null, key, data);

            /*
             * Now generate trace and commit log entries. The trace records
             * aren't forced out, but the commit records are forced.
             */
            FileManager.WRITE_COUNT = 0;
            FileManager.THROW_ON_WRITE = true;
            FileManager.STOP_ON_WRITE_COUNT = exceptionStartWrite;
            FileManager.N_BAD_WRITES = exceptionWriteCount;
            for (int i = 0; i < numIterations; i++) {

                try {
                    /* Generate a non-forced record. */
                    if (i == (numIterations - 1)) {

                        /*
                         * On the last iteration, write a record that is large
                         * enough to force a file flip (i.e. an fsync which
                         * succeeds) followed by the large write (which doesn't
                         * succeed due to an IOException).  In [#15754] the
                         * large write fails on Out Of Disk Space, rolling back
                         * the savedLSN to the previous file, even though the
                         * file has flipped.  The subsequent write ends up in
                         * the flipped file, but at the offset of the older
                         * file (leaving a hole in the new flipped file).
                         */
                        Trace.trace(envImpl,
                                    i + "/" + FileManager.WRITE_COUNT +
                                    " " + new String(new byte[2000]));
                    } else {
                        Trace.trace(envImpl,
                                        i + "/" + FileManager.WRITE_COUNT +
                                        " " + "xx");
                    }
                } catch (IllegalStateException ISE) {
                    /* Eat exception thrown by TraceLogHandler. */
                }

                /*
                 * Generate a forced record by calling commit. Since RMW
                 * transactions that didn't actually do a write won't log a
                 * commit record, do an addLogInfo to trick the txn into
                 * logging a commit.
                 */
                Transaction txn = env.beginTransaction(null, null);
                db.get(txn, key, data, LockMode.RMW);
                DbInternal.getTxn(txn).addLogInfo(DbLsn.makeLsn(3, 3));
                txn.commit();
            }
            db.close();

            /*
             * Verify that the log files are ok and have no checksum errors.
             */
            FileReader reader =
                new FileReader(DbInternal.getEnvironmentImpl(env),
                               4096, true, 0, null, DbLsn.NULL_LSN,
                               DbLsn.NULL_LSN) {
                    @Override
            protected boolean processEntry(ByteBuffer entryBuffer) {
                        entryBuffer.position(entryBuffer.position() +
                                             currentEntryHeader.getItemSize());
                        return true;
                    }
                };

            DbInternal.getEnvironmentImpl(env).getLogManager().flush();

            while (reader.readNextEntry()) {
            }

            /* Make sure the reader really did scan the files. */
            assert (DbLsn.getFileNumber(reader.getLastLsn()) == 3) :
                DbLsn.toString(reader.getLastLsn());

            env.close();
            env = null;
            db = null;
        } catch (Throwable T) {
            T.printStackTrace();
        } finally {
            FileManager.STOP_ON_WRITE_COUNT = Long.MAX_VALUE;
            FileManager.N_BAD_WRITES = Long.MAX_VALUE;
        }
    }

    /*
     * Test the following sequence:
     *
     * write LN, commit;
     * write same LN (getting an IOException),
     * write another LN (getting an IOException) verify fails due to must-abort
     * either commit(should fail and abort automatically)
     * or abort (should always succeed).
     * Verify UP, ensuring that LSN of LN is not marked obsolete.
     */
    @Test
    public void testSR15761Part1() {
        doSR15761Test(true);
    }

    @Test
    public void testSR15761Part2() {
        doSR15761Test(false);
    }

    private void doSR15761Test(boolean doCommit) {
        try {
            createDatabase(100000, 0, false);

            Transaction txn = env.beginTransaction(null, null);
            int keyInt = 0;
            String keyStr;

            keyStr = Integer.toString(keyInt);
            DatabaseEntry key = new DatabaseEntry(StringUtils.toUTF8(keyStr));
            DatabaseEntry data = new DatabaseEntry(new byte[2888]);
            try {
                assertTrue(db.put(txn, key, data) == OperationStatus.SUCCESS);
            } catch (DatabaseException DE) {
                fail("should have completed");
            }
            txn.commit();

            EnvironmentStats stats = env.getStats(null);
            int nLocksPrePut = stats.getNTotalLocks();
            txn = env.beginTransaction(null, null);
            FileManager.IO_EXCEPTION_TESTING_ON_WRITE = true;
            try {
                data = new DatabaseEntry(new byte[10000]);
                assertTrue(db.put(txn, key, data) == OperationStatus.SUCCESS);
                fail("expected IOException");
            } catch (DatabaseException DE) {
                /* Expected */
            }

            FileManager.IO_EXCEPTION_TESTING_ON_WRITE = false;
            try {
                data = new DatabaseEntry(new byte[10]);
                assertTrue(db.put(txn, key, data) == OperationStatus.SUCCESS);
                fail("expected IOException");
            } catch (OperationFailureException ISE) {
                /* Expected */
            }

            FileManager.IO_EXCEPTION_TESTING_ON_WRITE = false;
            if (doCommit) {
                try {
                    txn.commit();
                    fail("expected must-abort transaction exception");
                } catch (OperationFailureException DE) {
                    /* Expected. */
                }
            }
            try {
                txn.abort();
            } catch (DatabaseException DE) {
                fail("expected abort to succeed");
            }

            /* Lock should not be held. */
            stats = env.getStats(null);
            int nLocksPostPut = stats.getNTotalLocks();
            assertTrue(nLocksPrePut == nLocksPostPut);

            UtilizationProfile up =
                DbInternal.getEnvironmentImpl(env).getUtilizationProfile();

            /*
             * Checkpoint the environment to flush all utilization tracking
             * information before verifying.
             */
            CheckpointConfig ckptConfig = new CheckpointConfig();
            ckptConfig.setForce(true);
            env.checkpoint(ckptConfig);

            assertTrue(up.verifyFileSummaryDatabase());
        } catch (Throwable T) {
            T.printStackTrace();
        }
    }

    @Test
    public void testAbortWithIOException()
        throws Throwable {

        Transaction txn = null;
        createDatabase(0, 0, true);
        writeAndVerify(null, false, "k1", "d1", false);
        writeAndVerify(null, true, "k2", "d2", false);
        writeAndVerify(null, false, "k3", "d3", false);

        FileManager.IO_EXCEPTION_TESTING_ON_WRITE = true;
        EnvironmentStats stats = env.getStats(null);
        int nLocksPreGet = stats.getNTotalLocks();

        /* Loop doing aborts until the buffer fills up and we get an IOE. */
        int keySuffix = 1;
        boolean done = false;
        while (!done) {
            txn = env.beginTransaction(null, null);

            DatabaseEntry key =
                new DatabaseEntry(StringUtils.toUTF8(("key" + keySuffix)));
            DatabaseEntry data = key;
            try {
                OperationStatus status = db.put(txn, key, data);
                assertTrue(status == (OperationStatus.SUCCESS));

                 stats = env.getStats(null);
            } catch (Exception e) {
                done = true;
            }

            try {
                   txn.abort();

                /*
                 * Keep going until we actually get an IOException from the
                 * buffer filling up.
                 */
                continue;
            } catch (DatabaseException DE) {
                done = true;
            }
        }

        FileManager.IO_EXCEPTION_TESTING_ON_WRITE = false;

        /* Lock should not be held. */
        stats = env.getStats(null);
        int nLocksPostAbort = stats.getNTotalLocks();
        assertTrue(nLocksPreGet == nLocksPostAbort);
    }

    private void doIOExceptionTest(boolean doRecovery)
        throws Throwable {

        Transaction txn = null;
        createDatabase(0, 0, true);
        writeAndVerify(null, false, "k1", "d1", doRecovery);
        writeAndVerify(null, true, "k2", "d2", doRecovery);
        writeAndVerify(null, false, "k3", "d3", doRecovery);

        txn = env.beginTransaction(null, null);
        writeAndVerify(txn, false, "k4", "d4", false);
        txn.abort();
        verify(null, true, "k4", doRecovery);
        verify(null, false, "k1", doRecovery);
        verify(null, false, "k3", doRecovery);

        txn = env.beginTransaction(null, null);
        writeAndVerify(txn, false, "k4", "d4", false);
        txn.commit();
        verify(null, false, "k4", doRecovery);

        txn = env.beginTransaction(null, null);
        writeAndVerify(txn, true, "k5", "d5", false);
        /* Ensure that writes after IOExceptions don't succeed. */
        writeAndVerify(txn, false, "k5a", "d5a", false);
        txn.abort();
        verify(null, true, "k5", doRecovery);
        verify(null, true, "k5a", doRecovery);

        txn = env.beginTransaction(null, null);

        EnvironmentStats stats = env.getStats(null);
        int nLocksPrePut = stats.getNTotalLocks();

        writeAndVerify(txn, false, "k6", "d6", false);
        writeAndVerify(txn, true, "k6a", "d6a", false);

        stats = env.getStats(null);
        try {
            txn.commit();
            fail("expected DatabaseException");
        } catch (DatabaseException DE) {
        }

        /* Lock should not be held. */
        stats = env.getStats(null);
        int nLocksPostCommit = stats.getNTotalLocks();
        assertTrue(nLocksPrePut == nLocksPostCommit);

        verify(null, true, "k6", doRecovery);
        verify(null, true, "k6a", doRecovery);

        txn = env.beginTransaction(null, null);
        writeAndVerify(txn, false, "k6", "d6", false);
        writeAndVerify(txn, true, "k6a", "d6a", false);
        writeAndVerify(txn, false, "k6b", "d6b", false);

        try {
            txn.commit();
        } catch (DatabaseException DE) {
            fail("expected success");
        }

        /*
         * k6a will still exist because the writeAndVerify didn't fail -- there
         * was no write.  The write happens at commit time.
         */
        verify(null, false, "k6", doRecovery);
        verify(null, false, "k6a", doRecovery);
        verify(null, false, "k6b", doRecovery);
    }

    private void writeAndVerify(Transaction txn,
                                boolean throwIOException,
                                String keyString,
                                String dataString,
                                boolean doRecovery)
        throws DatabaseException {

        DatabaseEntry key = new DatabaseEntry(StringUtils.toUTF8(keyString));
        DatabaseEntry data = new DatabaseEntry(StringUtils.toUTF8(dataString));
        FileManager.IO_EXCEPTION_TESTING_ON_WRITE = throwIOException;
        try {
            assertTrue(db.put(txn, key, data) == OperationStatus.SUCCESS);

            /*
             * We don't expect an IOException if we're in a transaction because
             * the put() only writes to the buffer, not the disk.  The write to
             * disk doesn't happen until the commit/abort.
             */
            if (throwIOException && txn == null) {
                fail("didn't catch DatabaseException.");
            }
        } catch (DatabaseException DE) {
            if (!throwIOException || txn != null) {
                fail("caught DatabaseException.");
            }
        }
        verify(txn, throwIOException, keyString, doRecovery);
    }

    private void verify(Transaction txn,
                        boolean expectFailure,
                        String keyString,
                        boolean doRecovery)
        throws DatabaseException {

        if (doRecovery) {
            db.close();
            forceCloseEnvOnly();
            createDatabase(0, 0, true);
        }
        DatabaseEntry key = new DatabaseEntry(StringUtils.toUTF8(keyString));
        DatabaseEntry returnedData = new DatabaseEntry();
        OperationStatus status =
            db.get(txn,
                   key,
                   returnedData,
                   LockMode.DEFAULT);
        assertTrue("status " + status + " txn " + (txn != null),
                   status == ((expectFailure && txn == null) ?
                              OperationStatus.NOTFOUND :
                              OperationStatus.SUCCESS));
    }

    private void createDatabase(long cacheSize, long maxFileSize, boolean dups)
        throws DatabaseException {

        EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setTransactional(true);
        envConfig.setAllowCreate(true);
        envConfig.setConfigParam
            (EnvironmentParams.NUM_LOG_BUFFERS.getName(), "2");
        envConfig.setConfigParam
            (EnvironmentParams.LOG_MEM_SIZE.getName(),
             EnvironmentParams.LOG_MEM_SIZE_MIN_STRING);
        if (maxFileSize != 0) {
            DbInternal.disableParameterValidation(envConfig);
            envConfig.setConfigParam
                (EnvironmentParams.LOG_FILE_MAX.getName(), "" + maxFileSize);
        }
        if (cacheSize != 0) {
            envConfig.setCacheSize(cacheSize);
        }
        env = new Environment(envHome, envConfig);

        String databaseName = "ioexceptiondb";
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setSortedDuplicates(dups);
        dbConfig.setTransactional(true);
        db = env.openDatabase(null, databaseName, dbConfig);
    }

    /* Force the environment to be closed even with outstanding handles.*/
    private void forceCloseEnvOnly()
        throws DatabaseException {

        /* Close w/out checkpointing, in order to exercise recovery better.*/
        try {
            DbInternal.getEnvironmentImpl(env).close(false);
        } catch (DatabaseException DE) {
            if (!FileManager.IO_EXCEPTION_TESTING_ON_WRITE) {
                throw DE;
            } else {
                /* Expect an exception from flushing the log manager. */
            }
        }
        env = null;
    }
}
