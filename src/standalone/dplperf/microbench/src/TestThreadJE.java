/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002,2008 Oracle and/or its affiliates.  All rights reserved.
 */
package dplperf.microbench.src;

import java.util.Random;

import com.sleepycat.bind.tuple.TupleBinding;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.CursorConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DeadlockException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;

/**
 * TestThreadJE - a class for the threads used by the TestJE class.
 */
public class TestThreadJE extends TestThread {
    protected Environment env; // environment
    protected Database db; // database
    private DatabaseEntry key = null;
    private DatabaseEntry data = null;
    TupleBinding<String> strTupleBinding = null;
    TupleBinding<Integer> intTupleBinding = null;

    public TestThreadJE(TestJE test,
                        int repeat,
                        int id,
                        Environment env,
                        Database db,
                        Random rnd,
                        Microbench.TestConfig testConfig) {
        super(test, repeat, id, rnd, testConfig);
        this.env = env;
        this.db = db;

        key = new DatabaseEntry();
        data = new DatabaseEntry();
        strTupleBinding = TupleBinding.getPrimitiveBinding(String.class);
        intTupleBinding = TupleBinding.getPrimitiveBinding(Integer.class);
    }

    /** 
     */
    protected void assignKey(int val) {
        if (testConfig.entryType.equals("String")) {
            intTupleBinding.objectToEntry(val, key);
        } else {
            try {
                ComplexKey ctk = new ComplexKey(val);
                ((TestJE) test).getKeyBinding().objectToEntry(ctk, key);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    protected void selectData() 
        throws Exception {
        
        if (testConfig.entryType.equals("String")) {
            String s = "The quick fox jumps over the lazy dog.";
            strTupleBinding.objectToEntry(s, data);
        } else if (testConfig.entryType.equals("Basic")) {
            BasicEntity tuple = new BasicEntity();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, data);
        } else if (testConfig.entryType.equals("SimpleTypes")) {
            SimpleTypesEntity tuple = new SimpleTypesEntity();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, data);
        } else if (testConfig.entryType.equals("PrimitiveTypes")) {
            PrimitiveTypesEntity tuple = new PrimitiveTypesEntity();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, data);
        } else if (testConfig.entryType.equals("StringType")) {
            StringTypeEntity tuple = new StringTypeEntity();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, data);
        } else if (testConfig.entryType.equals("ArrayTypes")) {
            ArrayTypesEntity tuple = new ArrayTypesEntity();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, data);
        } else if (testConfig.entryType.equals("EnumTypes")) {
            EnumTypesEntity tuple = new EnumTypesEntity();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, data);
        } else if (testConfig.entryType.equals("ProxyTypes")) {
            ProxyTypesEntity tuple = new ProxyTypesEntity();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, data);
        } else if (testConfig.entryType.equals("Subclass")) {
            SubclassEntity tuple = new SubclassEntity();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, data);
        } else {
            throw new IllegalArgumentException
                ("not recognized entrytype: " + testConfig.entryType);
        }
    }
    
    /*
     */
    protected void modifyData(DatabaseEntry entry) throws Exception {
        
        if (testConfig.entryType.equals("String")) {
            String s = "The lazy dog jumps over the quick fox.";
            strTupleBinding.objectToEntry(s, entry);
        } else if (testConfig.entryType.equals("Basic")) {
            BasicEntity tuple = new BasicEntity(0);
            tuple.modify();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, entry);
        } else if (testConfig.entryType.equals("SimpleTypes")) {
            SimpleTypesEntity tuple = new SimpleTypesEntity(0);
            tuple.modify();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, entry);
        } else if (testConfig.entryType.equals("PrimitiveTypes")) {
            PrimitiveTypesEntity tuple = new PrimitiveTypesEntity(0);
            tuple.modify();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, entry);
        } else if (testConfig.entryType.equals("StringType")) {
            StringTypeEntity tuple = new StringTypeEntity(0);
            tuple.modify();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, entry);
        } else if (testConfig.entryType.equals("ArrayTypes")) {
            ArrayTypesEntity tuple = new ArrayTypesEntity(0);
            tuple.modify();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, entry);
        } else if (testConfig.entryType.equals("EnumTypes")) {
            EnumTypesEntity tuple = new EnumTypesEntity(0);
            tuple.modify();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, entry);
        } else if (testConfig.entryType.equals("ProxyTypes")) {
            ProxyTypesEntity tuple = new ProxyTypesEntity(0);
            tuple.modify();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, entry);
        } else if (testConfig.entryType.equals("Subclass")) {
            SubclassEntity tuple = new SubclassEntity(0);
            tuple.modify();
            ((TestJE) test).getTupleBinding().objectToEntry(tuple, entry);
        } else {
            throw new IllegalArgumentException
                ("not recognized entrytype: " + testConfig.entryType);
        }
    }

    protected Object beginTransaction() throws Exception {

        return env.beginTransaction(null, null);
    }

    protected void abortTxn(Object txn) throws Exception {

        ((Transaction) txn).abort();
    }

    protected void commitTxn(Object txn) throws Exception {

        if (testConfig.syncCommit) {
            ((Transaction) txn).commitSync();
        } else {
            ((Transaction) txn).commitNoSync();
        }
    }

    protected void exitIfNotDeadlock(Exception e) {

        if (!(e instanceof DeadlockException)) {
            System.err.println("unexpected exception: " + e);
            e.printStackTrace();
            System.exit(EXIT_FAILURE);
        }
    }

    protected void databasePutNoOverwrite(Object txn) throws Exception {
        selectData();
        OperationStatus status =
            db.putNoOverwrite((Transaction) txn, key, data);
        if (status == OperationStatus.KEYEXIST) {
            numConflicts++;
            status = OperationStatus.SUCCESS;
        }
    }

    protected void databaseUpdate(Object txn) throws Exception {
        
        DatabaseEntry newData = new DatabaseEntry();
        modifyData(newData);

        OperationStatus status = db.put((Transaction) txn, key, newData);

        if (status != OperationStatus.SUCCESS) {
            throw new RuntimeException("could not put [" +
                    new String(key.getData()) + "]. Status = " + status);
        }
    }
    
    protected void databaseGet(Object txn) throws Exception {

        OperationStatus status = db.get((Transaction) txn, key, data,
                                        testConfig.dirtyRead ?
                                        LockMode.READ_UNCOMMITTED :
                                        LockMode.DEFAULT);
        ((TestJE) test).getTupleBinding().entryToObject(data);
        ((TestJE) test).getKeyBinding().entryToObject(key);

        if (status != OperationStatus.SUCCESS) {
            System.err.println("Warning: could not read [" + key.getData() +
                    "]. Status = " + status);
            return;
        }
    }

    protected int scan() throws DatabaseException {
        int numRecords = 0;
        CursorConfig config = new CursorConfig();
        if (testConfig.dirtyRead) {
            config.setReadUncommitted(true);
        }

        Transaction txn = null;
        if (testConfig.useTxns) {
            txn = env.beginTransaction(null, null);
        }

        Cursor cursor = db.openCursor(txn, config);
        while (cursor.getNext(key, data, LockMode.DEFAULT) ==
            OperationStatus.SUCCESS) {
            numRecords++;
        }
        cursor.close();
        if (txn != null) {
            txn.commit();
        }
        System.out.println("Scan " + numRecords + " records.");
        return numRecords;
    }

    protected void databaseDelete(Object txn) throws Exception {

        OperationStatus status = db.delete((Transaction) txn, key);
        if (status != OperationStatus.SUCCESS) {
            numConflicts++;
            System.out.println("key=" + key + " status=" + status);
        }
    }
}
