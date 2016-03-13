package com.sleepycat.je.rep.sync;

import org.junit.Test;

import com.sleepycat.je.Database;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.sync.impl.LogChangeReaderTestBase;

/*
 * Test the LogChangeReader's behaviors on the ReplicatedEnvironments.
 */
public class RepLogChangeReaderTest extends LogChangeReaderTestBase {
    private RepEnvInfo[] repEnvInfo;

    @Override
    protected void createEnvironment() 
        throws Exception {

        repEnvInfo = RepSyncTestUtils.setupEnvInfos(3, envHome);
        env = RepTestUtils.joinGroup(repEnvInfo);
    }

    @Override
    protected void doLogFileFlip() {
        RepSyncTestUtils.doLogFileFlip(repEnvInfo);
    }

    /* Test the LogChangeReader reads the log correctly. */
    @Test
    public void testReaderBehaviors() 
        throws Exception {

        createEnvironment();
        try {
            createTransactionLog();

            Database fakeDB = RepSyncTestUtils.makeFakeEntries(env, newValue);
             
            doCommonCheck();

            fakeDB.close();
            dupDb.close();
            db.close();
        } finally {
            for (RepEnvInfo repInfo : repEnvInfo) {
                repInfo.getEnv().close();
            }
        }
    }
}
