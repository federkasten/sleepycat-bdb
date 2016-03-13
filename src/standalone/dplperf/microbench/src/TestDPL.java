/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002,2008 Oracle and/or its affiliates.  All rights reserved.
 */
package dplperf.microbench.src;

import java.io.File;
import java.util.Random;

import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.persist.EntityCursor;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.PrimaryIndex;
import com.sleepycat.persist.StoreConfig;
import com.sleepycat.persist.impl.PersistCatalog;
import com.sleepycat.persist.impl.PersistEntityBinding;

@SuppressWarnings("unchecked")
public class TestDPL extends Test {

    //private Environment env;
    private EntityStore store;
    //private Microbench.Stats stats;
    private PrimaryIndex myPrimaryIndex = null;

    public TestDPL(Microbench.TestConfig testConfig) {
        super(testConfig);
    }

    public void setup() throws Exception {
        /* Create a new, transactional database environment. */
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(testConfig.useTxns);
        envConfig.setTxnNoSync(!testConfig.syncCommit);
        envConfig.setCacheSize(testConfig.cacheSize);
        env = new Environment(new File(testConfig.envHome), envConfig);
        
        /* Open the entity store. */
        StoreConfig storeConfig = new StoreConfig();
        storeConfig.setAllowCreate(testConfig.createDb);
        storeConfig.setTransactional(testConfig.useTxns);
        store = new EntityStore(env, "EntityStore", storeConfig);
        if (testConfig.entryType.equals("String")) {
            myPrimaryIndex = store.getPrimaryIndex
                (Integer.class, MyEntity.class);
        } else if (testConfig.entryType.equals("Basic")) {
            myPrimaryIndex = store.getPrimaryIndex
                (ComplexKey.class, BasicEntity.class);
        } else if (testConfig.entryType.equals("SimpleTypes")) {
            myPrimaryIndex = store.getPrimaryIndex
                (ComplexKey.class, SimpleTypesEntity.class);
        } else if (testConfig.entryType.equals("PrimitiveTypes")) {
            myPrimaryIndex = store.getPrimaryIndex
                (ComplexKey.class, PrimitiveTypesEntity.class);
        } else if (testConfig.entryType.equals("StringType")) {
            myPrimaryIndex = store.getPrimaryIndex
            (ComplexKey.class, StringTypeEntity.class);
        } else if (testConfig.entryType.equals("ArrayTypes")) {
            myPrimaryIndex = store.getPrimaryIndex
                (ComplexKey.class, ArrayTypesEntity.class);
        } else if (testConfig.entryType.equals("EnumTypes")) {
            myPrimaryIndex = store.getPrimaryIndex
                (ComplexKey.class, EnumTypesEntity.class);
        } else if (testConfig.entryType.equals("ProxyTypes")) {
            myPrimaryIndex = store.getPrimaryIndex
                (ComplexKey.class, ProxyTypesEntity.class);
        } else if (testConfig.entryType.equals("Subclass")) {
            myPrimaryIndex = store.getPrimaryIndex
                (ComplexKey.class, BasicEntity.class);
        } else {
            throw new IllegalArgumentException
                ("not recognized entrytype: " + testConfig.entryType);
        }
        
        /* Check if the binding works. */
        try {
            checkBinding();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
        
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
        return new TestThreadDPL(this, repeat, whichThread, store,
                myPrimaryIndex, rnd, testConfig);
    }

    public void close() throws Exception {

        try {
            if (store != null) {
                store.close();
            }

            if (env != null) {
                env.close();
            }
        } catch (DatabaseException DE) {
            System.err.println("Caught " + DE + " while closing env and store");
        }
    }

    public void preload() throws Exception {
    
        System.out.println("Preload start");
        EntityCursor entities = myPrimaryIndex.entities();
        try {
            for (Object o : entities) {}
        } finally {
            entities.close();
        }
        System.out.println("Preload end");
    }

    public String getVersion() {
        return "DPL with JE " + testConfig.version;
    }
    
    protected void checkBinding() throws Exception {
        System.out.println("Check if the binding works.");
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        PersistCatalog catalog = new PersistCatalog
            (env, "EntityStore", "EntityStore" + "catalog", dbConfig, null,
             null, false /*rawAccess*/, null /*Store*/);
        
        DatabaseEntry keyEntry = new DatabaseEntry();
        DatabaseEntry dataEntry = new DatabaseEntry();
        if (testConfig.entryType.equals("String")) {
            PersistEntityBinding entityBinding = new PersistEntityBinding
                (catalog, MyEntity.class.getName(), false);
            MyEntity entity = new MyEntity(0);
            entityBinding.objectToData(entity, dataEntry);
            entityBinding.objectToKey(entity, keyEntry);
            MyEntity entity2 = 
                (MyEntity) entityBinding.entryToObject(keyEntry, dataEntry);
            if (!entity.equals(entity2)) {
                throw new Exception
                ("The binding for MyEntity class doesn't works");
            }
        } else if (testConfig.entryType.equals("Basic")) {
            PersistEntityBinding entityBinding = new PersistEntityBinding
                (catalog, BasicEntity.class.getName(), false);
            BasicEntity entity = new BasicEntity(0);
            entityBinding.objectToData(entity, dataEntry);
            entityBinding.objectToKey(entity, keyEntry);
            BasicEntity entity2 = 
                (BasicEntity) entityBinding.entryToObject(keyEntry, dataEntry);
            if (!entity.equals(entity2)) {
                throw new Exception
                ("The binding for BasicEntity class doesn't works");
            }
        } else if (testConfig.entryType.equals("SimpleTypes")) {
            PersistEntityBinding entityBinding = new PersistEntityBinding
                (catalog, SimpleTypesEntity.class.getName(), false);
            SimpleTypesEntity entity = new SimpleTypesEntity(0);
            entityBinding.objectToData(entity, dataEntry);
            entityBinding.objectToKey(entity, keyEntry);
            SimpleTypesEntity entity2 = (SimpleTypesEntity) entityBinding.
                entryToObject(keyEntry, dataEntry);
            if (!entity.equals(entity2)) {
                throw new Exception
                ("The binding for SimpleTypesEntity class doesn't works");
            }
        } else if (testConfig.entryType.equals("PrimitiveTypes")) {
            PersistEntityBinding entityBinding = new PersistEntityBinding
                (catalog, PrimitiveTypesEntity.class.getName(), false);
            PrimitiveTypesEntity entity = new PrimitiveTypesEntity(0);
            entityBinding.objectToData(entity, dataEntry);
            entityBinding.objectToKey(entity, keyEntry);
            PrimitiveTypesEntity entity2 = (PrimitiveTypesEntity) 
                entityBinding.entryToObject(keyEntry, dataEntry);
            if (!entity.equals(entity2)) {
                throw new Exception
                ("The binding for PrimitiveTypesEntity class doesn't works");
            }   
        } else if (testConfig.entryType.equals("StringType")) {
            PersistEntityBinding entityBinding = new PersistEntityBinding
                (catalog, StringTypeEntity.class.getName(), false);
            StringTypeEntity entity = new StringTypeEntity(0);
            entityBinding.objectToData(entity, dataEntry);
            entityBinding.objectToKey(entity, keyEntry);
            StringTypeEntity entity2 = (StringTypeEntity) entityBinding.
                entryToObject(keyEntry, dataEntry);
            if (!entity.equals(entity2)) {
                throw new Exception
                    ("The binding for StringTypeEntity class doesn't works");
            }
        } else if (testConfig.entryType.equals("ArrayTypes")) {
            PersistEntityBinding entityBinding = new PersistEntityBinding
                (catalog, ArrayTypesEntity.class.getName(), false);
            ArrayTypesEntity entity = new ArrayTypesEntity(0);
            entityBinding.objectToData(entity, dataEntry);
            entityBinding.objectToKey(entity, keyEntry);
            ArrayTypesEntity entity2 = (ArrayTypesEntity) entityBinding.
                entryToObject(keyEntry, dataEntry);
            if (!entity.equals(entity2)) {
                throw new Exception
                ("The binding for ArrayTypesEntity class doesn't works");
            }
        } else if (testConfig.entryType.equals("EnumTypes")) {
            PersistEntityBinding entityBinding = new PersistEntityBinding
                (catalog, EnumTypesEntity.class.getName(), false);
            EnumTypesEntity entity = new EnumTypesEntity(0);
            entityBinding.objectToData(entity, dataEntry);
            entityBinding.objectToKey(entity, keyEntry);
            EnumTypesEntity entity2 = (EnumTypesEntity) entityBinding.
                entryToObject(keyEntry, dataEntry);
            if (!entity.equals(entity2)) {
                throw new Exception
                ("The binding for EnumTypesEntity class doesn't works");
            }
        } else if (testConfig.entryType.equals("ProxyTypes")) {
            PersistEntityBinding entityBinding = new PersistEntityBinding
                (catalog, ProxyTypesEntity.class.getName(), false);
            ProxyTypesEntity entity = new ProxyTypesEntity(0);
            entityBinding.objectToData(entity, dataEntry);
            entityBinding.objectToKey(entity, keyEntry);
            ProxyTypesEntity entity2 = (ProxyTypesEntity) entityBinding.
                entryToObject(keyEntry, dataEntry);
            if (!entity.equals(entity2)) {
                throw new Exception
                ("The binding for ProxyTypesEntity class doesn't works");
            }
        } else if (testConfig.entryType.equals("Subclass")) {
            PersistEntityBinding entityBinding = new PersistEntityBinding
                (catalog, SubclassEntity.class.getName(), false);
            SubclassEntity entity = new SubclassEntity(0);
            entityBinding.objectToData(entity, dataEntry);
            entityBinding.objectToKey(entity, keyEntry);
            SubclassEntity entity2 = (SubclassEntity) entityBinding.
                entryToObject(keyEntry, dataEntry);
            if (!entity.equals(entity2)) {
                throw new Exception
                ("The binding for SubclassEntity class doesn't works");
            }
        } else {
            catalog.close();
            catalog = null;
            throw new IllegalArgumentException
                ("not recognized entrytype: " + testConfig.entryType);
        }
        catalog.close();
        catalog = null;
    }
}
