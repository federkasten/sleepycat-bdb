package com.sleepycat.je.sync.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Iterator;

import org.junit.After;
import org.junit.Test;

import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.sync.ChangeReader.ChangeTxn;

/*
 * Test LogChangeReader's basic behaviors on the standalone side.
 */
public class LogChangeReaderTest extends LogChangeReaderTestBase {
    
    @Override
    protected void createEnvironment() 
        throws Exception {

        /* Create the Environment. */
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);

        env = new Environment(envHome, envConfig);
    }

    @Override
    protected void doLogFileFlip() {
        DbInternal.getEnvironmentImpl(env).forceLogFileFlip();
    }

    @After
    public void tearDown()
        throws Exception {

        env.close();
    }

    /* Test the LogChangeReader reads the log correctly. */
    @Test
    public void testReaderBehaviors() 
        throws Exception {

        createTransactionLog();

        doCommonCheck();

        /* Continue do more updates. */
        createTransactionalLog
            (true, db, OpType.INSERT, 321, 400, oldValue, expectedTxns);

        /* 
         * Don't do a log file flip, and the default log file size is 10M, 
         * we're sure the above changes won't cause a file flip. Because we'll
         * only read log entries on the second largest log file, we'll read 
         * nothging this time.
         */
        Iterator<ChangeTxn> changeTxns = reader.getChangeTxns();
        assertFalse(changeTxns.hasNext());

        /* Do a log flie flip, so that we can read all the log changes now. */
        DbInternal.getEnvironmentImpl(env).forceLogFileFlip();

        changeTxns = reader.getChangeTxns();
        int counter = 0;
        while (changeTxns.hasNext()) {
            doCheck(changeTxns, expectedTxns);
            counter++;
        }
        assertTrue(counter == 1);

        dupDb.close();
        db.close();
    }
}
