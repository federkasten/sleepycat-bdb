package com.sleepycat.je.sync.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Iterator;

import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.sync.ChangeReader.ChangeTxn;
import com.sleepycat.je.sync.impl.LogChangeReader;
import com.sleepycat.je.sync.impl.LogChangeSet;

public class MockMobileTest extends MockMobileTestBase {

    @Override
    protected void createEnvAndDbs() 
        throws Exception {

        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);

        env = new Environment(envHome, envConfig);

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        dbConfig.setTransactional(true);

        dbA = env.openDatabase(null, dbAName, dbConfig);
        dbB = env.openDatabase(null, dbBName, dbConfig); 
    }

    @Override
    protected void doLogFileFlip() 
        throws Exception {

        DbInternal.getEnvironmentImpl(env).forceLogFileFlip();
    }

    @Override
    protected void closeEnvAndDbs() {
        if (dbA != null) {
            dbA.close();
        }

        if (dbB != null) {
            dbB.close();
        }

        if (env != null) {
            env.close();
        }
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
        assertTrue(minSyncStart <
                   envImpl.getSyncCleanerBarrier().getMinSyncStart());

        /* Add more transaction logs. */
        doDbOperations(dbA, 31, 40, oldValue, false);
        doDbOperations(dbA, 41, 50, oldValue, false);
        doLogFileFlip();

        changeTxns = reader.getChangeTxns();

        /* Delete the SyncDataSet. */
        processor.removeDataSet(dbAName);
        /* Check that minSyncStart becomes LogChangeSet.NULL_POSITION. */
        assertTrue(LogChangeSet.NULL_POSITION ==
                   envImpl.getSyncCleanerBarrier().getMinSyncStart());
    }
}
