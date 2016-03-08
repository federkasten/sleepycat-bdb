/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002,2008 Oracle and/or its affiliates.  All rights reserved.
 */
package dplperf.microbench.src;

import java.io.File;
import java.util.Random;

import com.sleepycat.bind.EntryBinding;
import com.sleepycat.bind.tuple.TupleBinding;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;

class TestJE extends Test {
    /* For binding */
    private Database catalogDb;
    private TupleBinding tupleBinding = null;//new MyTupleBinding();
    private TupleBinding keyBinding = new MyComplexKeyBinding();
    
    public TestJE(Microbench.TestConfig testConfig) {
        super(testConfig);
    }

    public void setup() throws Exception {
        /* Create a new, transactional database environment. */
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(testConfig.useTxns);
        envConfig.setCacheSize(testConfig.cacheSize);
        long t1 = System.currentTimeMillis();
        env = new Environment(new File(testConfig.envHome), envConfig);
        long t2 = System.currentTimeMillis();

        /* Open the database */
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setTransactional(testConfig.useTxns);

        if (testConfig.createDb) {
            dbConfig.setAllowCreate(true);
            dbConfig.setExclusiveCreate(true);
        }

        db = env.openDatabase(null, "testDb", dbConfig);
        long t3 = System.currentTimeMillis();

        System.out.println("recovery time = " + (t2 - t1) + " ms");
        System.out.println("db open time = " + (t3 - t2) + " ms");
        
        if (tupleBinding == null) {
            if (testConfig.entryType.equals("String")) {
                tupleBinding = TupleBinding.getPrimitiveBinding(String.class);
            } else if (testConfig.entryType.equals("Basic")) {
                tupleBinding = new MyBasicBinding();
            } else if (testConfig.entryType.equals("SimpleTypes")) {
                tupleBinding = new MySimpleTypesBinding();
            } else if (testConfig.entryType.equals("PrimitiveTypes")) {
                tupleBinding = new MyPrimitiveTypesBinding();
            } else if (testConfig.entryType.equals("StringType")) {
                tupleBinding = new MyStringTypeBinding();
            } else if (testConfig.entryType.equals("ArrayTypes")) {
                tupleBinding = new MyArrayTypesBinding();
            } else if (testConfig.entryType.equals("EnumTypes")) {
                tupleBinding = new MyEnumTypesBinding();
            } else if (testConfig.entryType.equals("ProxyTypes")) {
                tupleBinding = new MyProxyTypesBinding();
            } else if (testConfig.entryType.equals("Subclass")) {
                tupleBinding = new MySubclassBinding();
            } else {
                throw new IllegalArgumentException
                    ("not recognized entrytype: " + testConfig.entryType);
            }
        }
        
        /* Check if the binding works. */
        try {
            checkBinding();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }

        /* Preload the database if requested. */
        if (testConfig.preload) {
            preload();
        }
        
        /* Dump stats now, to clear stats from preload and recovery effect. */
        stats = new Microbench.Stats();
        if (testConfig.verbose) {
            System.out.println("=====> Pre-test stats <=====");
            stats.printEnvStats(env, true);
            stats.printINListSize(env);
            stats.printLockStats(env, true);
            System.out.println("=====> End pre-test stats  <=====");
        }
    }

    public TestThread createThread(int repeat, int whichThread, Random rnd) {
        return new TestThreadJE(this, repeat, whichThread, env, db, rnd, 
                                testConfig);
    }

    public void close() throws Exception {
        if (catalogDb != null) {
            catalogDb.close();
        }
        db.close();
        env.close();
    }

    /*
     * Don't just call Environment.preload, we want to load all the LNs too.
     */
    public void preload() throws DatabaseException {

        System.out.println("Preload start");
        Cursor cursor = db.openCursor(null, null);
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry();

        while (cursor.getNext(key, data, LockMode.READ_UNCOMMITTED) ==
            OperationStatus.SUCCESS) {
        }
        cursor.close();
        System.out.println("Preload end");
    }
    
    Microbench.TestConfig getConfig() {
        return testConfig;
    }
    
    public EntryBinding getTupleBinding() throws Exception {
        return tupleBinding;
    }
    
    public EntryBinding getKeyBinding() {
        return keyBinding;
    }
    
    public String getVersion() {
        return "JE " + testConfig.version;
    }
    
    protected void checkBinding() throws Exception {
        System.out.println("Check if the binding works.");
        DatabaseEntry data = new DatabaseEntry();;
        if (testConfig.entryType.equals("String")) {
            String s = "The quick fox jumps over the lazy dog.";
            TupleBinding<String> strTupleBinding = 
                TupleBinding.getPrimitiveBinding(String.class);
            strTupleBinding.objectToEntry(s, data);
            String s2 = strTupleBinding.entryToObject(data);
            if (!s.equals(s2)) {
                throw new Exception
                    ("The binding for String class doesn't works");
            }
        } else if (testConfig.entryType.equals("Basic")) {
            BasicEntity tuple = new BasicEntity(0);
            getTupleBinding().objectToEntry(tuple, data);
            BasicEntity tuple2 = 
                (BasicEntity) getTupleBinding().entryToObject(data);
            tuple2.setKey(new ComplexKey(0));
            if (!tuple.equals(tuple2)) {
                throw new Exception
                    ("The binding for BasicEntity class doesn't works");
            }
        } else if (testConfig.entryType.equals("SimpleTypes")) {
            SimpleTypesEntity tuple = new SimpleTypesEntity(0);
            getTupleBinding().objectToEntry(tuple, data);
            SimpleTypesEntity tuple2 = (SimpleTypesEntity) 
                getTupleBinding().entryToObject(data);
            tuple2.setKey(new ComplexKey(0));
            if (!tuple.equals(tuple2)) {
                throw new Exception
                    ("The binding for SimpleTypesEntity class doesn't works");
            }
        } else if (testConfig.entryType.equals("PrimitiveTypes")) {
            PrimitiveTypesEntity tuple = new PrimitiveTypesEntity(0);
            getTupleBinding().objectToEntry(tuple, data);
            PrimitiveTypesEntity tuple2 = (PrimitiveTypesEntity) 
                getTupleBinding().entryToObject(data);
            tuple2.setKey(new ComplexKey(0));
            if (!tuple.equals(tuple2)) {
                throw new Exception
                    ("The binding for PrimitiveTypesEntity class doesn't works");
            }
        } else if (testConfig.entryType.equals("StringType")) {
            StringTypeEntity tuple = new StringTypeEntity(0);
            getTupleBinding().objectToEntry(tuple, data);
            StringTypeEntity tuple2 = (StringTypeEntity) 
                getTupleBinding().entryToObject(data);
            tuple2.setKey(new ComplexKey(0));
            if (!tuple.equals(tuple2)) {
                throw new Exception
                    ("The binding for StringTypeEntity class doesn't works");
            }
        } else if (testConfig.entryType.equals("ArrayTypes")) {
            ArrayTypesEntity tuple = new ArrayTypesEntity(0);
            getTupleBinding().objectToEntry(tuple, data);
            ArrayTypesEntity tuple2 = (ArrayTypesEntity) 
                getTupleBinding().entryToObject(data);
            tuple2.setKey(new ComplexKey(0));
            if (!tuple.equals(tuple2)) {
                throw new Exception
                    ("The binding for ArrayTypesEntity class doesn't works");
            }
        } else if (testConfig.entryType.equals("EnumTypes")) {
            EnumTypesEntity tuple = new EnumTypesEntity(0);
            getTupleBinding().objectToEntry(tuple, data);
            EnumTypesEntity tuple2 = (EnumTypesEntity) 
                getTupleBinding().entryToObject(data);
            tuple2.setKey(new ComplexKey(0));
            if (!tuple.equals(tuple2)) {
                throw new Exception
                    ("The binding for EnumTypesEntity class doesn't works");
            }
        } else if (testConfig.entryType.equals("ProxyTypes")) {
            ProxyTypesEntity tuple = new ProxyTypesEntity(0);
            getTupleBinding().objectToEntry(tuple, data);
            ProxyTypesEntity tuple2 = (ProxyTypesEntity) 
                getTupleBinding().entryToObject(data);
            tuple2.setKey(new ComplexKey(0));
            if (!tuple.equals(tuple2)) {
                throw new Exception
                    ("The binding for ProxyTypesEntity class doesn't works");
            }
        } else if (testConfig.entryType.equals("Subclass")) {
            SubclassEntity tuple = new SubclassEntity(0);
            getTupleBinding().objectToEntry(tuple, data);
            SubclassEntity tuple2 = 
                (SubclassEntity) getTupleBinding().entryToObject(data);
            tuple2.setKey(new ComplexKey(0));
            if (!tuple.equals(tuple2)) {
                throw new Exception
                    ("The binding for SubclassEntity class doesn't works");
            }
        } else {
            throw new IllegalArgumentException
                ("not recognized entrytype: " + testConfig.entryType);
        }
    }
}
