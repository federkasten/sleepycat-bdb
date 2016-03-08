/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.impl.node;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.TimerTask;

import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseNotFoundException;
import com.sleepycat.je.Durability;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentStats;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.ReplicationConfig;
import com.sleepycat.je.rep.ReplicationMutableConfig;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/**
 * Test that the LogFlusher does work as we expect.
 */
public class LogFlusherTest extends TestBase {
    private static final String dbName = "testDb";
    private static final String value = "herococo";
    /* Replication tests use multiple environments. */
    private final File envRoot;
    private RepEnvInfo[] repEnvInfo;

    public LogFlusherTest() {
        envRoot = SharedTestUtils.getTestDir();
    }

    /**
     * Test the basic configuration of LogFlusher.
     */
    @Test
    public void testBasicConfig()
        throws Throwable {

        try {
            EnvironmentConfig envConfig = 
                RepTestUtils.createEnvConfig(Durability.COMMIT_NO_SYNC);
            ReplicationConfig repConfig = new ReplicationConfig();
            repConfig.setConfigParam
                (ReplicationMutableConfig.LOG_FLUSH_TASK_INTERVAL, "30 s");
            repEnvInfo = 
                RepTestUtils.setupEnvInfos(envRoot, 3, envConfig, repConfig);
            RepTestUtils.joinGroup(repEnvInfo);
            assertTrue(repEnvInfo[0].isMaster());
            /* Check the LogFlusher configuration. */
            TimerTask[] oldTasks = new TimerTask[repEnvInfo.length];
            for (int i = 0; i < repEnvInfo.length; i++) {
                LogFlusher flusher = 
                    repEnvInfo[i].getRepNode().getLogFlusher();
                oldTasks[i] = flusher.getFlushTask();
                assertTrue(flusher != null);
                assertTrue(flusher.getFlushTask() != null);
                assertTrue(flusher.getFlushInterval() == 30000);
            }

            /* Check that those configuratins are mutable. */
            repConfig.setConfigParam
                (ReplicationMutableConfig.LOG_FLUSH_TASK_INTERVAL, "50 s");
            for (int i = 0; i < repEnvInfo.length; i++) {
                repEnvInfo[i].getEnv().setRepMutableConfig(repConfig);
            }

            for (int i = 0; i < repEnvInfo.length; i++) {
                LogFlusher flusher = 
                    repEnvInfo[i].getRepNode().getLogFlusher();
                assertTrue(flusher != null);
                assertTrue(flusher.getFlushTask() != null);
                assertTrue(flusher.getFlushTask() != oldTasks[i]);
                assertTrue(flusher.getFlushInterval() == 50000);
            }

            repConfig.setConfigParam
                (ReplicationMutableConfig.RUN_LOG_FLUSH_TASK, "false");
            for (int i = 0; i < repEnvInfo.length; i++) {
                repEnvInfo[i].getEnv().setRepMutableConfig(repConfig);
            }

            for (int i = 0; i < repEnvInfo.length; i++) {
                LogFlusher flusher =
                    repEnvInfo[i].getRepNode().getLogFlusher();
                assertTrue(flusher != null);
                assertTrue(flusher.getFlushTask() == null);
            }

            RepTestUtils.shutdownRepEnvs(repEnvInfo);
            RepTestUtils.removeRepEnvironments(envRoot);

            repConfig.setConfigParam
                (ReplicationConfig.RUN_LOG_FLUSH_TASK, "false");
            repEnvInfo = 
                RepTestUtils.setupEnvInfos(envRoot, 3, envConfig, repConfig);
            RepTestUtils.joinGroup(repEnvInfo);
            /* Check that the task is disabled. */
            for (int i = 0; i < repEnvInfo.length; i++) {
                assertTrue
                    (repEnvInfo[i].getRepNode().getLogFlusher() == null);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        } finally {
            RepTestUtils.shutdownRepEnvs(repEnvInfo);
        }
    }

    /**
     * Test that the LogFlushTask does flush those dirty data to the log, and
     * can be read after crash.
     */
    @Test
    public void testTaskDoesFlush()
        throws Throwable {

        experienceLogFlushTask("15 s", true);
    }

    /**
     * Test that if the LogFlushTask doesn't flush the updates before the 
     * crash, no data is written to the disk.
     */
    @Test
    public void testEnvCrashesBeforeFlush() 
        throws Throwable {

        experienceLogFlushTask("30 s", false);
    }

    private void createRepEnvInfo(String sleepTime) 
        throws Throwable {

        /*
         * Set a large buffer size and disable the checkpointing, so the
         * data in the buffer can only be flushed by the LogFlushTask.
         */
        EnvironmentConfig envConfig =
            RepTestUtils.createEnvConfig(Durability.COMMIT_NO_SYNC);
        envConfig.setConfigParam
            (EnvironmentParams.MAX_MEMORY.getName(), "20000000");
        envConfig.setConfigParam
            (EnvironmentParams.LOG_MEM_SIZE.getName(), "120000000");
        envConfig.setConfigParam
            (EnvironmentParams.NUM_LOG_BUFFERS.getName(), "4");
        envConfig.setConfigParam
            (EnvironmentConfig.ENV_RUN_CHECKPOINTER, "false"); 

        /* Configure the log flush task. */
        ReplicationConfig repConfig = new ReplicationConfig();
        repConfig.setConfigParam
            (ReplicationConfig.LOG_FLUSH_TASK_INTERVAL, sleepTime);
        repEnvInfo = 
            RepTestUtils.setupEnvInfos(envRoot, 3, envConfig, repConfig);
    }

    private void experienceLogFlushTask(String sleepTime, 
                                        boolean flushBeforeCrash) 
        throws Throwable {

        try {
            createRepEnvInfo(sleepTime);

            ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);
            long startTime = System.currentTimeMillis();

            StatsConfig stConfig = new StatsConfig();
            stConfig.setClear(true);

            /* Flush the existed dirty data before we do writes. */
            for (int i = 0; i < repEnvInfo.length; i++) {
                repEnvInfo[i].getEnv().sync();
                repEnvInfo[i].getEnv().getStats(stConfig);
            }

            DatabaseConfig dbConfig = new DatabaseConfig();
            dbConfig.setAllowCreate(true);
            dbConfig.setTransactional(true);

            Database db = master.openDatabase(null, dbName, dbConfig);

            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry data = new DatabaseEntry();
            for (int i = 1; i <= 100; i++) {
                IntegerBinding.intToEntry(i, key);
                StringBinding.stringToEntry(value, data);
                db.put(null, key, data);
            }
            
            assertTrue(System.currentTimeMillis() - startTime < 15000);

            Thread.sleep(15000);

            long endTime = System.currentTimeMillis();

            for (int i = 0; i < repEnvInfo.length; i++) {
                EnvironmentStats envStats = 
                    repEnvInfo[i].getEnv().getStats(stConfig);
                LogFlusher flusher = 
                    repEnvInfo[i].getRepNode().getLogFlusher();
                if (flushBeforeCrash) {
                    /* Make sure the LogFlushTask has been invoked. */
                    assertTrue(flusher.getFlushTask().scheduledExecutionTime()
                               > startTime);
                    assertTrue(flusher.getFlushTask().scheduledExecutionTime() 
                               < endTime);

                    /* 
                     * Since the log file size is not so big, we can't assure 
                     * all the data will be written in the same log file, but
                     * we can sure that a flush does happen.
                     */
                    assertTrue(envStats.getNSequentialWrites() >= 1);
                    assertTrue(envStats.getNLogFSyncs() == 1);
                } else {

                    /*
                     * Make sure the LogFlushTask is not invoked after making 
                     * the changes. 
                     */
                    assertTrue(flusher.getFlushTask().scheduledExecutionTime() 
                               < startTime);
                    assertTrue(envStats.getNSequentialWrites() == 0);
                    assertTrue(envStats.getNLogFSyncs() == 0);
                }
                assertTrue(envStats.getNFSyncs() == 0);
            }

            File[] envHomes = new File[3];
            /* Close the replicas without doing a checkpoint. */
            for (int i = 0; i < repEnvInfo.length; i++) {
                envHomes[i] = repEnvInfo[i].getEnvHome();
                repEnvInfo[i].getRepImpl().abnormalClose();
            }

            /* 
             * Open a read only standalone Environment on the replicas to see 
             * whether the data has been synced to the disk.
             */
            EnvironmentConfig newConfig = new EnvironmentConfig();
            newConfig.setAllowCreate(false);
            newConfig.setReadOnly(true);
            newConfig.setTransactional(true);

            for (int i = 0; i < repEnvInfo.length; i++) {
                Environment env = new Environment(envHomes[i], newConfig);

                dbConfig.setAllowCreate(false);
                dbConfig.setReadOnly(true); 

                try {
                    db = env.openDatabase(null, dbName, dbConfig);
                } catch (DatabaseNotFoundException e) {

                    /*
                     * If the system crashes before the flush, the database is
                     * not synced to the disk, so this database can't be found
                     * at all, it's expected.
                     */
                    assertFalse(flushBeforeCrash);
                }

                if (flushBeforeCrash) {
                    assertTrue(db.count() == 100);
                    for (int index = 1; index <= 100; index++) {
                        IntegerBinding.intToEntry(index, key);
                        OperationStatus status = db.get(null, key, data, null);
                        if (flushBeforeCrash) {
                            assertTrue(status == OperationStatus.SUCCESS);
                            assertEquals(value, 
                                         StringBinding.entryToString(data));
                        } 
                    }
                }

                if (flushBeforeCrash) {
                    db.close();
                }
                env.close();
            }
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        }
    }
}
