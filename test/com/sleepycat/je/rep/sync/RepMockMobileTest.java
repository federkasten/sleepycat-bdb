package com.sleepycat.je.rep.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Iterator;

import com.sleepycat.je.Database;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.rep.RepInternal;
import com.sleepycat.je.rep.impl.RepImpl;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.sync.ChangeReader.ChangeTxn;
import com.sleepycat.je.sync.impl.LogChangeReader;
import com.sleepycat.je.sync.impl.LogChangeSet;
import com.sleepycat.je.sync.test.MockMobileTestBase;
import com.sleepycat.je.sync.test.TestMobileSyncProcessor;

public class RepMockMobileTest extends MockMobileTestBase {
    private RepEnvInfo[] repEnvInfo;

    @Override
    protected void createEnvAndDbs() 
        throws Exception {

        repEnvInfo = RepSyncTestUtils.setupEnvInfos(3, envHome);
        env = RepTestUtils.joinGroup(repEnvInfo);
        dbA = RepSyncTestUtils.createDb(env, dbAName);
        dbB = RepSyncTestUtils.createDb(env, dbBName);
    }

    @Override
    protected void doLogFileFlip() 
        throws Exception {

        RepSyncTestUtils.doLogFileFlip(repEnvInfo);

        Database db = RepSyncTestUtils.makeFakeEntries(env, oldValue);

        db.close();
    }

    @Override
    protected void closeEnvAndDbs() {
        if (dbA != null) {
            dbA.close();
        }

        if (dbB != null) {
            dbB.close();
        }

        RepTestUtils.shutdownRepEnvs(repEnvInfo);
    }

    @Override
    protected void doCommitAndAbortCheck(TestMobileSyncProcessor processor,
                                         LogChangeReader reader) 
        throws Exception {

        EnvironmentImpl envImpl = DbInternal.getEnvironmentImpl(env);

        /* Get the original minSyncStart. */
        long minSyncStart = envImpl.getSyncCleanerBarrier().getMinSyncStart();
        Iterator<ChangeTxn> changeTxns = reader.getChangeTxns();

        /* Check that aborted transaction doesn't affect the minSyncStart. */
        changeTxns.next();
        Transaction txn = env.beginTransaction(null, null);
        reader.discardChanges(txn);
        txn.abort();
        assertEquals(minSyncStart,
                     envImpl.getSyncCleanerBarrier().getMinSyncStart());

        /* Check that committed transaction affect the minSyncStart. */
        changeTxns.next();
        txn = env.beginTransaction(null, null);
        reader.discardChanges(txn);
        txn.commit();
        RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);

        /* Check that commits do replay on the replicas. */
        for (int i = 0; i < repEnvInfo.length; i++) {
            RepImpl rImpl = RepInternal.getRepImpl(repEnvInfo[i].getEnv());
            assertTrue(minSyncStart <
                       rImpl.getSyncCleanerBarrier().getMinSyncStart());
        }

        /* Delete the SyncDataSet. */
        processor.removeDataSet(dbAName);
        RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);

        /* Check that minSyncStart becomes LogChangeSet.NULL_POSITION. */
        for (int i = 0; i < repEnvInfo.length; i++) {
            RepImpl rImpl = RepInternal.getRepImpl(repEnvInfo[i].getEnv());
            assertTrue(LogChangeSet.NULL_POSITION ==
                       rImpl.getSyncCleanerBarrier().getMinSyncStart());
        }
    }
}
