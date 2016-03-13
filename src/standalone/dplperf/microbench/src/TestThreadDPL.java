/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002,2008 Oracle and/or its affiliates.  All rights reserved.
 */
package dplperf.microbench.src;

import java.util.Random;

import com.sleepycat.je.CursorConfig;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.Transaction;
import com.sleepycat.persist.EntityCursor;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.PrimaryIndex;

public class TestThreadDPL extends TestThread {
    private EntityStore store;
    private PrimaryIndex primaryIndex;
    private int key;

    public TestThreadDPL(TestDPL test,
                         int repeat,
                         int id,
                         EntityStore store,
                         PrimaryIndex primaryIndex,
                         Random rnd,
                         Microbench.TestConfig testConfig) {
        super(test, repeat, id, rnd, testConfig);
        this.store = store;
        this.primaryIndex = primaryIndex;
    }

    protected void assignKey(int val) {
        key = val;
    }

    protected Object beginTransaction()
        throws Exception {

        try {
            Transaction txn =
                store.getEnvironment().beginTransaction(null, null);
            return txn;
        } catch (DatabaseException DE) {
            System.out.println("Caught " + DE + " during beginTransaction");
            return null;
        }
    }

    protected void abortTxn(Object txn)
        throws Exception {

        Transaction t = (Transaction) txn;

        try {
            t.abort();
        } catch (DatabaseException DE) {
            System.out.println("Caught " + DE + " during abort");
        }
    }

    protected void commitTxn(Object txn)
        throws Exception {

        Transaction t = (Transaction) txn;

        try {
            if (testConfig.syncCommit) {
                t.commitSync();
            } else {
                t.commitNoSync();
            }
        } catch (DatabaseException DE) {
            System.out.println("Caught " + DE + " during abort");
        }
    }

    protected void exitIfNotDeadlock(Exception e) {

        System.err.println("unexpected exception: " + e);
        e.printStackTrace();
        System.exit(EXIT_FAILURE);
    }

    protected void databasePutNoOverwrite(Object txn)
        throws Exception {
        if (testConfig.entryType.equals("String")) {
            MyEntity entity = new MyEntity(key);
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("Basic")) {
            BasicEntity entity = new BasicEntity(key);
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("SimpleTypes")) {
            SimpleTypesEntity entity = new SimpleTypesEntity(key);
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("PrimitiveTypes")) {
            PrimitiveTypesEntity entity = new PrimitiveTypesEntity(key);
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("StringType")) {
            StringTypeEntity entity = new StringTypeEntity(key);
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("ArrayTypes")) {
            ArrayTypesEntity entity = new ArrayTypesEntity(key);
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("EnumTypes")) {
            EnumTypesEntity entity = new EnumTypesEntity(key);
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("ProxyTypes")) {
            ProxyTypesEntity entity = new ProxyTypesEntity(key);
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("Subclass")) {
            SubclassEntity entity = new SubclassEntity(key);
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else {
            throw new IllegalArgumentException
                ("not recognized entrytype: " + testConfig.entryType);
        }
    }

    @SuppressWarnings("unchecked")
    protected void databaseUpdate(Object txn)
        throws Exception {

        Transaction t = (Transaction) txn;
        if (testConfig.entryType.equals("String")) {
            MyEntity entity = new MyEntity(key);
            entity.modify();
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("Basic")) {
            BasicEntity entity = new BasicEntity(key);
            entity.modify();
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("SimpleTypes")) {
            SimpleTypesEntity entity =new SimpleTypesEntity(key);
            entity.modify();
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("PrimitiveTypes")) {
            PrimitiveTypesEntity entity = new PrimitiveTypesEntity(key);
            entity.modify();
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("StringType")) {
            StringTypeEntity entity = new StringTypeEntity(key);
            entity.modify();
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("ArrayTypes")) {
            ArrayTypesEntity entity = new ArrayTypesEntity(key);
            entity.modify();
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("EnumTypes")) {
            EnumTypesEntity entity = new EnumTypesEntity(key);
            entity.modify();
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("ProxyTypes")) {
            ProxyTypesEntity entity = new ProxyTypesEntity(key);
            entity.modify();
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else if (testConfig.entryType.equals("Subclass")) {
            SubclassEntity entity = new SubclassEntity(key);
            entity.modify();
            primaryIndex.putNoReturn((Transaction) txn, entity);
        } else {
            throw new IllegalArgumentException
                ("not recognized entrytype: " + testConfig.entryType);
        }
    }

    protected void databaseGet(Object txn)
        throws Exception  {

        Object o = null;
        if (testConfig.entryType.equals("String")) {
            o = primaryIndex.get((Transaction) txn, key, LockMode.DEFAULT);
            
        } else {
            o = primaryIndex.get((Transaction) txn,
                                 new ComplexKey(key),
                                 LockMode.DEFAULT);
        }
        //System.out.println("Read entity = " +((MyEntity) o).getData());
    }

    protected int scan()
        throws Exception {

        int numRecords = 0;
        CursorConfig curConf = new CursorConfig();
        if (testConfig.dirtyRead) {
            curConf.setReadUncommitted(true);
            System.out.println("setting dirty read");
        }

        Object txn = null;
        if (testConfig.useTxns) {
            txn = beginTransaction();
        }

        EntityCursor cursor = primaryIndex.entities((Transaction) txn, curConf);
        try {
            for (Object o : cursor) {
                numRecords++;
            }
        } finally {
            cursor.close();
        }

        if (testConfig.useTxns) {
            commitTxn(txn);
        }

        System.out.println("scan records=" + numRecords);
        return numRecords;
    }

    protected void databaseDelete(Object txn)
        throws Exception {

        if (testConfig.entryType.equals("String")) {
            primaryIndex.delete((Transaction) txn, key);
        } else {
            primaryIndex.delete((Transaction) txn, new ComplexKey(key));
        }
    }
}
