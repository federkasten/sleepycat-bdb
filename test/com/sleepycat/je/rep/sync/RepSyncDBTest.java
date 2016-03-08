/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;

import org.junit.Test;

import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.dbi.DatabaseId;
import com.sleepycat.je.dbi.DatabaseImpl;
import com.sleepycat.je.dbi.DbType;
import com.sleepycat.je.rep.RepInternal;
import com.sleepycat.je.rep.ReplicaWriteException;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.impl.RepImpl;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.sync.impl.LogChangeSet;
import com.sleepycat.je.sync.impl.LogChangeSet.LogChangeSetBinding;
import com.sleepycat.je.sync.impl.SyncDB;
import com.sleepycat.je.sync.impl.SyncDB.OpType;
import com.sleepycat.je.utilint.VLSN;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

public class RepSyncDBTest extends TestBase {
    private static final String processorName = "processor";
    private static final String dataSetName = "test";

    private final File envRoot;
    private RepEnvInfo[] repEnvInfo;

    public RepSyncDBTest() {
        envRoot = SharedTestUtils.getTestDir();
    }

    @Test
    public void testBasic() 
        throws Throwable {

        try {
            repEnvInfo = RepTestUtils.setupEnvInfos(envRoot, 3);
            ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);
            assertTrue(master.getState().isMaster());

            ReplicatedEnvironment replica = repEnvInfo[1].getEnv();
            assertTrue(replica.getState().isReplica());

            try {
                SyncDB syncDb = 
                    new SyncDB(RepInternal.getRepImpl(replica), true);
                fail("Expect exceptions here.");
            } catch (ReplicaWriteException e) {
                /* Except exceptions. */
            }

            SyncDB syncDb = new SyncDB(RepInternal.getRepImpl(master), true);

            /* No records in the database currently. */
            assertTrue(syncDb.getCount() == 0);

            RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length); 

            DatabaseId syncDbId = syncDb.getDatabaseImpl().getId();

            /* Test that SyncDB has already replayed on the replicas. */
            for (RepEnvInfo repEnv : repEnvInfo) {
                RepImpl repImpl = RepInternal.getRepImpl(repEnv.getEnv());
                DatabaseImpl dbImpl = repImpl.getDbTree().getDb(syncDbId);
                assertTrue(dbImpl != null);
                repImpl.getDbTree().releaseDb(dbImpl);
            }

            /* Write a LogChangeSet to SyncDB. */
            LogChangeSet set = new LogChangeSet(100, 100);
            DatabaseEntry data = new DatabaseEntry();
            LogChangeSetBinding binding = new LogChangeSetBinding();
            binding.objectToEntry(set, data);
            Transaction txn = master.beginTransaction(null, null);
            RepInternal.getRepImpl(master).freezeLocalCBVLSN();
            syncDb.writeChangeSetData
                (master, txn, processorName, dataSetName, data, OpType.INSERT);
            txn.commit();

            /* Sync all replicas to a common point. */
            checkEquality(repEnvInfo);

            /* Open SyncDB on all replicas, check the record just written. */
            for (RepEnvInfo repEnv : repEnvInfo) {
                replica = repEnv.getEnv();

                DatabaseConfig dbConfig = new DatabaseConfig();
                dbConfig.setTransactional(true);
                dbConfig.setUseExistingConfig(true);

                Database db = replica.openDatabase
                    (null, DbType.SYNC.getInternalName(), dbConfig);

                assertTrue(db.count() == 1);

                DatabaseEntry key = new DatabaseEntry();
                data = new DatabaseEntry();

                StringBinding.stringToEntry
                    (processorName + "-" + dataSetName + "-1", key);
                db.get(null, key, data, null);
                assertTrue(data.getData() != null);
                LogChangeSet newSet = binding.entryToObject(data);
                assertEquals
                    (newSet.getNextSyncStart(), set.getNextSyncStart());
                assertEquals(newSet.getLastSyncEnd(), set.getLastSyncEnd());

                db.close();
            }
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        } finally {
            RepTestUtils.shutdownRepEnvs(repEnvInfo);
        }
    }

    private void checkEquality(RepEnvInfo[] repInfoArray) 
        throws Exception {

        VLSN vlsn = RepTestUtils.syncGroupToLastCommit(repInfoArray,
                                                       repInfoArray.length);
        RepTestUtils.checkNodeEquality(vlsn, false, repInfoArray);
    }
}
