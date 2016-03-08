/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Iterator;

import org.junit.After;
import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.rep.RepInternal;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.impl.RepImpl;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.sync.ChangeReader.ChangeTxn;
import com.sleepycat.je.sync.SyncProcessor;
import com.sleepycat.je.sync.impl.LogChangeReader;
import com.sleepycat.je.sync.impl.SyncCleanerBarrier;
import com.sleepycat.je.sync.impl.SyncDB;
import com.sleepycat.je.sync.impl.SyncDB.DataType;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

public class RepSyncTriggerTest extends TestBase {
    private static final String firstDBName = "firstDB";
    private static final String secDBName = "secDB";
    private static final String oldValue = "abcdefghijklmnopqrstuvwxyz";
    private static final String processorName = "processor";

    private final File envRoot;
    private RepEnvInfo[] repEnvInfo;

    public RepSyncTriggerTest() {
        envRoot = SharedTestUtils.getTestDir();
    }

    @After
    public void tearDown() {
        if (repEnvInfo != null) {
            try {
                RepTestUtils.shutdownRepEnvs(repEnvInfo);
            } catch (Throwable e) {
                System.out.println("tearDown: " + e);
            }
            repEnvInfo = null;
        }
    }

    /* 
     * Test that updates on the master SyncCleanerBarrier also updates 
     * correctly on the replicas by the SyncTrigger.
     */
    @Test
    public void testBasicBehaviors() 
        throws Exception {

        /* Open the replication group. */
        repEnvInfo = RepSyncTestUtils.setupEnvInfos(3, envRoot);
        ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);
        assertTrue(master.getState().isMaster());

        /* Open the databases. */
        Database firstDb = RepSyncTestUtils.createDb(master, firstDBName);
        Database secDb = RepSyncTestUtils.createDb(master, secDBName);

        /* Open the SyncProcessor. */
        String[] dbNames = new String[] { firstDBName, secDBName };
        SyncProcessor processor = RepSyncTestUtils.createProcessor
            (processorName, dbNames, master);

        SyncCleanerBarrier mBarrier = 
            RepInternal.getRepImpl(master).getSyncCleanerBarrier();

        final String firstKey = SyncDB.generateKey
            (processorName, firstDBName, DataType.CHANGE_SET);
        final String secKey = SyncDB.generateKey
            (processorName, secDBName, DataType.CHANGE_SET);
        long firstSyncStart = mBarrier.getSyncStart(firstKey);
        long secSyncStart = mBarrier.getSyncStart(secKey);

        RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);

        /* Check replicas' SyncCleanerBarrier are the same as master. */
        for (int i = 1; i < repEnvInfo.length; i++) {
            SyncCleanerBarrier rBarrier = RepInternal.getRepImpl
                (repEnvInfo[i].getEnv()).getSyncCleanerBarrier();
            assertEquals(rBarrier.getSyncStart(firstKey), firstSyncStart);
            assertEquals(rBarrier.getSyncStart(secKey), secSyncStart);
            assertEquals(rBarrier.getMinSyncStart(), firstSyncStart);
        } 

        /* Create transactional log. */
        Database[] dbs = new Database[] { firstDb, secDb };
        for (int i = 1; i <= 2; i++) {
            for (Database db : dbs) {
                RepSyncTestUtils.doDatabaseWork
                    (db, master, (i - 1) * 100 + 1, i * 100, 
                     oldValue, false);
            }
        }

        RepImpl mImpl = RepInternal.getRepImpl(master);

        /* Log entries that don't belong to SyncDataSets. */
        Database fakeDb = 
            RepSyncTestUtils.makeFakeEntries(master, oldValue);

        LogChangeReader firstReader = 
            new LogChangeReader(master, firstDBName, processor, false, 0);
        LogChangeReader secReader =
            new LogChangeReader(master, secDBName, processor, false, 0);

        Iterator<ChangeTxn> firstTxns = firstReader.getChangeTxns();
        Iterator<ChangeTxn> secTxns = secReader.getChangeTxns();

        /* Check that commits will cause updates on CleanerBarrier. */
        doCheck(master, mBarrier, firstReader, firstTxns, 
                secSyncStart, firstSyncStart, null, false);
        firstSyncStart = mBarrier.getSyncStart(firstKey);
           
        doCheck(master, mBarrier, secReader, secTxns, 
                firstSyncStart, secSyncStart, null, false);
        secSyncStart = mBarrier.getSyncStart(secKey);

        doCheck(master, mBarrier, firstReader, firstTxns,
                secSyncStart, firstSyncStart, null, false);
        firstSyncStart = mBarrier.getSyncStart(firstKey);

        /* Check that aborts won't cause updates on CleanerBarrier. */
        doCheck(master, mBarrier, secReader, secTxns,
                secSyncStart, firstSyncStart, fakeDb, true);
        fakeDb.close();

        /* Test SyncDataSet removal on the replicas. */
        processor.removeDataSet(secDBName);
        RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);

        assertTrue(mBarrier.getMinSyncStart() == firstSyncStart);
        for (int i = 1; i < repEnvInfo.length; i++) {
            RepImpl rImpl = RepInternal.getRepImpl(repEnvInfo[i].getEnv());
            SyncCleanerBarrier rBarrier = rImpl.getSyncCleanerBarrier();
            assertTrue(mBarrier.getMinSyncStart() ==
                       rBarrier.getMinSyncStart());
            assertTrue(rBarrier.getMinSyncStart() == firstSyncStart);
            assertTrue(rBarrier.getMinSyncStart() > secSyncStart);
        }

        firstDb.close();
        secDb.close();
        RepTestUtils.shutdownRepEnvs(repEnvInfo);
        repEnvInfo = null;
    }

    private void doCheck(ReplicatedEnvironment env,
                         SyncCleanerBarrier mBarrier, 
                         LogChangeReader reader,
                         Iterator<ChangeTxn> txns, 
                         long expectedSame, 
                         long expectedDiff,
                         Database db, 
                         boolean abort) 
        throws Exception {

        txns.next();
        Transaction txn = env.beginTransaction(null, null);
        reader.discardChanges(txn);
        if (abort) {
            txn.abort();
        } else {
            txn.commit();
        }

        if (abort) {
            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry data = new DatabaseEntry();
            IntegerBinding.intToEntry(1, key);
            StringBinding.stringToEntry("herococo", data);
            db.put(null, key, data);
        }
        RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);

        RepImpl mImpl = RepInternal.getRepImpl(env);

        assertTrue(mBarrier.getMinSyncStart() == expectedSame);
        for (int i = 1; i < repEnvInfo.length; i++) {
            RepImpl rImpl = RepInternal.getRepImpl(repEnvInfo[i].getEnv());
            SyncCleanerBarrier rBarrier = rImpl.getSyncCleanerBarrier();
            assertTrue(mBarrier.getMinSyncStart() ==
                       rBarrier.getMinSyncStart());
            assertTrue(rBarrier.getMinSyncStart() == expectedSame);
            if (abort) {
                assertTrue(rBarrier.getMinSyncStart() < expectedDiff);
            } else {
                assertTrue(rBarrier.getMinSyncStart() > expectedDiff);
            }
        }
    }
}
