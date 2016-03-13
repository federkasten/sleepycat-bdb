/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.After;
import org.junit.Test;

import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.evictor.Evictor.EvictionSource;
import com.sleepycat.je.log.FileManager;
import com.sleepycat.je.tree.BIN;
import com.sleepycat.je.tree.IN;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.je.utilint.TestHook;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/**
 * Tests coordination of eviction and checkpointing.
 */
public class CkptEvictCoordTest extends TestBase {

    private static final boolean DEBUG = false;

    private File envHome;
    private Environment env;
    private EnvironmentImpl envImpl;

    public CkptEvictCoordTest() {
        envHome = SharedTestUtils.getTestDir();
    }

    @After
    public void tearDown() {
        
        try {
            if (env != null) {
                env.close();
            }
        } catch (Throwable e) {
            System.out.println("tearDown: " + e);
        }

        env = null;
        envImpl = null;
        envHome = null;
    }

    /**
     * Opens the environment.
     */
    private void openEnv() {

        EnvironmentConfig config = TestUtils.initEnvConfig();
        config.setAllowCreate(true);

        /* Do not run the daemons. */
        config.setConfigParam
            (EnvironmentParams.ENV_RUN_CLEANER.getName(), "false");
        config.setConfigParam
            (EnvironmentParams.ENV_RUN_EVICTOR.getName(), "false");
        config.setConfigParam
            (EnvironmentParams.ENV_RUN_CHECKPOINTER.getName(), "false");
        config.setConfigParam
            (EnvironmentParams.ENV_RUN_INCOMPRESSOR.getName(), "false");
        /* Set max batch files to one, for exact control over cleaning. */
        config.setConfigParam
            (EnvironmentParams.CLEANER_MAX_BATCH_FILES.getName(), "1");
        /* Use a tiny log file size to write one IN per file. */
        DbInternal.disableParameterValidation(config);
        config.setConfigParam(EnvironmentParams.LOG_FILE_MAX.getName(),
                              Integer.toString(64));

        /*
         * Disable critical eviction, we want to test under controlled
         * circumstances.
         */
        config.setConfigParam
            (EnvironmentParams.EVICTOR_CRITICAL_PERCENTAGE.getName(), "1000");

        env = new Environment(envHome, config);
        envImpl = DbInternal.getEnvironmentImpl(env);
    }

    /**
     * Closes the environment.
     */
    private void closeEnv() {

        if (env != null) {
            env.close();
            env = null;
        }
    }

    /**
     * Verifies a fix for a LogFileNotFound issue that was introduced in JE 4.1
     * by removing synchronization of eviction.  Eviction and the construction
     * of the checkpointer dirty map were previously synchronized and could not
     * execute concurrently.  Rather than synchronize them again, the fix is to
     * make eviction log provisionally during construction of the dirty map,
     * and add the dirty parent of the evicted IN to the dirty map.
     *
     * This test creates the scenario described here:
     * https://sleepycat.oracle.com/trac/ticket/19346#comment:16
     *
     * [#19346]
     */
    @Test
    public void testEvictionDuringDirtyMapCreation() {

        openEnv();

        /* Start with nothing dirty. */
        env.sync();

        /*
         * We use the FileSummaryDB because it just so happens that when we
         * open a new environment, it has a single BIN and parent IN, and they
         * are iterated by the INList in the order required to reproduce the
         * problem: parent followed by child.  See the SR for details.
         */
        final long DB_ID = 2L; /* ID of FileSummaryDB is always 2. */

        /*
         * Find parent IN and child BIN.  Check that IN precedes BIN when
         * iterating the INList, which is necessary to reproduce the bug.
         */
        IN child = null;
        IN parent = null;

        for (IN in : envImpl.getInMemoryINs()) {
            if (in.getDatabase().getId().getId() == DB_ID) {
                if (in instanceof BIN) {
                    assertNull("Expect only one BIN", child);
                    child = in;
                } else {
                    if (child != null) {
                        System.out.println
                            ("WARNING: Test cannot be performed because IN " +
                             "parent does not precede child BIN");
                        closeEnv();
                        return;
                    }
                    assertNull("Expect only one IN", parent);
                    parent = in;
                }
            }
        }
        assertNotNull(child);
        assertNotNull(parent);

        /* We use tiny log files so that each IN is in a different file. */
        assertTrue(DbLsn.getFileNumber(child.getLastLoggedVersion()) !=
                   DbLsn.getFileNumber(parent.getLastLoggedVersion()));

        if (DEBUG) {
            System.out.println("child node=" + child.getNodeId() + " LSN=" +
                               DbLsn.getNoFormatString
                               (child.getLastLoggedVersion()) +
                               " dirty=" + child.getDirty());
            System.out.println("parent node=" + parent.getNodeId() + " LSN=" +
                               DbLsn.getNoFormatString
                               (parent.getLastLoggedVersion()) +
                               " dirty=" + parent.getDirty());
        }

        /*
         * Clean the log file containing the child BIN.  Because we set
         * CLEANER_MAX_BATCH_FILES to 1, this will clean only the single file
         * that we inject below.
         */
        final long fileNum = DbLsn.getFileNumber(child.getLastLoggedVersion());
        envImpl.getCleaner().getFileSelector().injectFileForCleaning(fileNum);

        final long filesCleaned = envImpl.getCleaner().doClean
            (false /*cleanMultipleFiles*/, false /*forceCleaning*/);
        assertEquals(1, filesCleaned);

        /* Parent must not be dirty.  Child must be dirty after cleaning. */
        assertFalse(parent.getDirty());
        assertTrue(child.getDirty());

        final IN useChild = child;
        final IN useParent = parent;

        /* Hook called after examining each IN during dirty map creation. */
        class DirtyMapHook implements TestHook<IN> {
            boolean sawChild;
            boolean sawParent;

            public void doHook(IN in) {

                /*
                 * The parent IN is iterated first, before the child BIN.  It
                 * is not dirty, so it will not be added to the dirty map.  We
                 * evict the child BIN at this time, so that the child will not
                 * be added to the dirty map.
                 *
                 * The eviction creates the condition for the bug described in
                 * the SR, which is that the child is logged in the checkpoint
                 * interval but the parent is not, and the child is logged
                 * non-provisionally.  With the bug fix, the child is logged
                 * provisionally by the evictor and the parent is added to the
                 * dirty map at that time, so that it will be logged by the
                 * checkpoint.
                 *
                 * Ideally, to simulate real world conditions, this test should
                 * do the eviction in a separate thread.  However, because
                 * there was no synchronization between checkpointer and
                 * evictor, the effect of doing it in the same thread is the
                 * same.  Even with the bug fix, there is still no
                 * synchronization between checkpointer and evictor at the time
                 * the hook is called.
                 */
                if (in == useParent) {
                    assertFalse(sawChild);
                    assertFalse(sawParent);
                    assertFalse(in.getDirty());
                    sawParent = true;
                    /* First eviction strips LNs, second evicts IN. */
                    envImpl.getEvictor().doEvictOneIN
                        (useChild, EvictionSource.MANUAL);
                    envImpl.getEvictor().doEvictOneIN
                        (useChild, EvictionSource.MANUAL);
                    assertFalse(useChild.getInListResident());
                }

                /*
                 * We shouldn't see the child BIN because it was evicted, but
                 * if we do see it then it should not be dirty.
                 */
                if (in == useChild) {
                    assertFalse(sawChild);
                    assertTrue(sawParent);
                    sawChild = true;
                    assertFalse(in.getDirty());
                }
            }

            /* Unused methods. */
            public void doHook() {
                throw new UnsupportedOperationException();
            }
            public IN getHookValue() {
                throw new UnsupportedOperationException();
            }
            public void doIOHook() {
                throw new UnsupportedOperationException();
            }
            public void hookSetup() {
                throw new UnsupportedOperationException();
            }
        };

        /*
         * Perform checkpoint and perform eviction during construction of the
         * dirty map, using the hook above.
         */
        final DirtyMapHook hook = new DirtyMapHook();
        Checkpointer.examineINForCheckpointHook = hook;
        try {
            env.checkpoint(new CheckpointConfig().setForce(true));
        } finally {
            Checkpointer.examineINForCheckpointHook = null;
        }
        assertTrue(hook.sawParent);

        /* Checkpoint should have deleted the file. */
        final String fileName = envImpl.getFileManager().getFullFileName
            (fileNum, FileManager.JE_SUFFIX);
        assertFalse(fileName, new File(fileName).exists());

        /* Crash and recover. */
        envImpl.abnormalClose();
        envImpl = null;
        /* Before the bug fix, this recovery caused LogFileNotFound. */
        openEnv();
        closeEnv();
    }
}
