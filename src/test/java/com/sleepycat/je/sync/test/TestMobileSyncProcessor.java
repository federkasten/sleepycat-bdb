/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.sync.test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.sleepycat.bind.tuple.TupleBinding;
import com.sleepycat.bind.tuple.TupleInput;
import com.sleepycat.bind.tuple.TupleOutput;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.Durability;
import com.sleepycat.je.Environment;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.je.sync.ChangeReader;
import com.sleepycat.je.sync.ExportConfig;
import com.sleepycat.je.sync.ImportConfig;
import com.sleepycat.je.sync.ProcessorMetadata;
import com.sleepycat.je.sync.RecordConverter;
import com.sleepycat.je.sync.SyncDataSet;
import com.sleepycat.je.sync.SyncDatabase;
import com.sleepycat.je.sync.SyncProcessor;
import com.sleepycat.je.sync.ChangeReader.Change;
import com.sleepycat.je.sync.ChangeReader.ChangeTxn;
import com.sleepycat.je.sync.ChangeReader.ChangeType;
import com.sleepycat.je.sync.mobile.MobileConnectionConfig;

/**
 * To the best of our knowledge, this class simulates the sync algorithm that
 * will be necessary in the future when implementing MobileSyncProcessor.
 * This class uses the MockMobile interfaces, which are very similar to the
 * classes in the Mobile Server client library.
 *
 * MobileSyncProcessor itself will not be implemented in the first version of
 * JE Data Sync.  But it is important to test the ChangeReader and other
 * support classes with the Mobile Sync algorithm, to ensure they will work
 * well with the Mobile Server client library in the future.
 *
 * When we do implement the real MobileSyncProcessor in the future, we can
 * start with this class and modify it as necessary.
 *
 * This class should not use JE internal APIs.  It is important that the public
 * API in the sync package is sufficient, since this ensures that the public
 * API is also sufficient for users to implement custom sync processors.
 */
public class TestMobileSyncProcessor extends SyncProcessor {

    private MobileConnectionConfig config;
    private MockMobile.OSESession session;
    private JEPlugin plugin;

    /* 
     * To avoid each data retrieve to read the database, create a global 
     * MyProcessorMetadata for use.
     */
    private MyProcessorMetadata procMetadata;

    public TestMobileSyncProcessor(Environment env,
                                   String processorName,
                                   MobileConnectionConfig connectionConfig,
                                   char[] password) {
        super(env, processorName);

        Transaction txn = env.beginTransaction(null, null);

        /* Read MobileConnectionConfig from SyncDB. */
        procMetadata = (MyProcessorMetadata) readProcessorMetadata(txn);

        if (connectionConfig != null) {

            /* 
             * Write the new connectionConfig to SyncDB, create a new 
             * MyProcessorMetadata if there is no metadata for this 
             * processor. 
             */
            if (procMetadata == null) {
                procMetadata = new MyProcessorMetadata();
            }
            config = connectionConfig.clone();
            procMetadata.setConfigMetadata(config);
            writeProcessorMetadata(txn, procMetadata);
        } else {

            /*
             * If no metadata for this processor and connectionConfig is null,
             * throw out an IllegalArgumentException, otherwise initiate the 
             * config from SyncDB.
             */
            if (procMetadata == null) {
                throw new IllegalArgumentException();
            }

            config = procMetadata.getConfigMetadata();
        }

        txn.commit();
    }

    /**
     * Return the number of exported records, for testing only.
     */
    public int getExportedRecords() {
        return session.getExportedRecords();
    }

    /**
     * Set the Metadata for the SyncProcessor, write it to the SyncDB, mainly 
     * for the publication and Snapshots information.
     */
    public void setMetadata(MockMobile.Metadata metadata) {
        procMetadata.setPluginMetadata(metadata);
        writeProcessorMetadata(null, procMetadata);
    }

    /**
     * Returns the connection configuration.
     */
    public MobileConnectionConfig getConnectionConfig() {
        return config.clone();
    }

    /**
     * Changes the connection configuration.
     */
    public void setConnectionConfig(MobileConnectionConfig connectionConfig) {
        /* Set config as the clone of connectionConfig. */
        config = connectionConfig.clone();

        /* 
         * Reset the MobileConnectionConfig for MyProcessorMetdata and write 
         * it into SyncDB. 
         */
        procMetadata.setConfigMetadata(config);
        writeProcessorMetadata(null, procMetadata);
    }

    /**
     * @param dataSetName must be the same as the Oracle mobile publication
     * name.
     */
    public SyncDataSet addDataSet(String dataSetName,
                                  Collection<SyncDatabase> databases) {
        MySyncDataSet dataSet = 
            new MySyncDataSet(dataSetName, this, databases);
        procMetadata.addDataSet(dataSet);

        writeProcessorMetadata(null, procMetadata);

        /* Register SyncDataSet in SyncCleanerBarrier to control cleaner. */
        registerDataSet(dataSet);

        return dataSet;
    }

    public void removeDataSet(String dataSetName) {
        procMetadata.removeDataSet(dataSetName);
        writeProcessorMetadata(null, procMetadata);
        unregisterDataSet(dataSetName);
    }

    public Map<String, SyncDataSet> getDataSets() {
        Map<String, SyncDataSet> syncDataSets = 
            new HashMap<String, SyncDataSet>();

        for (SyncDataSet dataSet : procMetadata.getDataSets()) {
            syncDataSets.put(dataSet.getName(), dataSet);
        }

        return syncDataSets;
    }

    public void syncAll(ExportConfig exportConfig, ImportConfig importConfig) {
        doSync(exportConfig, importConfig);
    }

    public void sync(ExportConfig exportConfig,
                     ImportConfig importConfig,
                     String... dataSetName) {
        assert dataSetName.length > 0;
        doSync(exportConfig, importConfig, dataSetName);
    }

    private synchronized void doSync(ExportConfig exportConfig,
                                     ImportConfig importConfig,
                                     String... dataSetName) {
        try {
            /* Only one sync allowed at a time. TODO: Throw JE exception. */
            assert session == null;

            /*
             * User name, password and URL are specified, although they are not
             * used in the mock session object.
             */
            session = new MockMobile.OSESession
                (config.getUserName(), config.getPassword());
            session.setURL(config.getURL());

            /* Ignore other MobileConnectionConfig properties for now. */

            /*
             * The JE plugin is explicitly associated with the session here,
             * although in the real iConnect library this association will be
             * done differently.
             */
            plugin = new JEPlugin(exportConfig, importConfig);

            session.setPlugin(plugin);

            /* For now, only configure the RefreshAll import property. */
            session.setForceRefresh(importConfig.getRefreshAll());

            /* Specify which data sets to sync. */
            if (dataSetName.length > 0) {
                for (String name : dataSetName) {
                    session.selectPub(name);
                }
            }

            /* Perform sync.  OSESession will call the JEPlugin as needed. */
            session.sync();

        } catch (MockMobile.OSEException e) {
            /* TODO: wrap with JE exception. */
            throw new RuntimeException(e);
        }
    }

    public void cancelSync() {
        /* Sync must be in progress. TODO: Throw JE exception. */
        assert session != null;
        try {
            session.cancelSync();
        } catch (MockMobile.OSEException e) {
            /* TODO: wrap with JE exception. */
            throw new RuntimeException(e);
        }
    }

    /**
     * An approximation of the JE plugin class for the iConnect library.
     */
    private class JEPlugin implements MockMobile.Plugin {

        private JEMobileDatabase mobileDb;
        private MockMobile.Metadata metadata;

        public JEPlugin(ExportConfig exportConfig, ImportConfig importConfig) {
            mobileDb = new JEMobileDatabase(exportConfig, importConfig);
        }

        /**
         * Called once per plugin instance before it is used for a sync.
         */
        public void init(String user, char [] pwd)
            throws MockMobile.PluginException {
        }
                
        /**
         * Called once per plugin instance after the sync is complete.
         */
        public void close()
            throws MockMobile.PluginException {
        }

        /**
         * We will support blobs.
         */
        public boolean supportsBlobs() {
            return true;
        }
        
        /**
         * To be honest we don't know how queues work or whether we should
         * support them.  For now, we won't.
         */
        public boolean supportsQueues() {
            return false;
        }
        
        /**
         * The database (meaning RDBMS or set of tables) name is fixed for JE.
         * There is only one logical set of tables per JE Environment.
         */
        public String defaultDBName()
            throws MockMobile.PluginException {

            return "JE";
        }
        
        /**
         * Not sure if this will be called.  Do not support, for now.
         */
        public void setDebug(boolean toDebug)
            throws MockMobile.PluginException {

            throw new UnsupportedOperationException();
        }

        /**
         * We will not support background sync, since a JE user can perform a
         * sync in a background thread if they choose.
         */
        public void setBackground(boolean isBackground)
            throws MockMobile.PluginException {

            throw new UnsupportedOperationException();
        }

        /**
         * Not sure if this will be called.  Do not support, for now.
         */
        public void createDatabase(String name, byte [] info)
            throws MockMobile.PluginException {

            throw new UnsupportedOperationException();
        }

        /**
         * For JE there is only one database (RDBMS or collection of tables)
         * per JE Environment.
         */
        public MockMobile.Database openDatabase(String name)
            throws MockMobile.PluginException {

            return mobileDb;
        }

        /**
         * With the real iConnect library, this method will probably be passed
         * a byte or char array.  But for testing, we use Java serialization of
         * the metadata object.
         */
        public MockMobile.Metadata readMetadata()
            throws MockMobile.PluginException {

            metadata = procMetadata.getPluginMetadata();

            if (metadata == null) {
                metadata = new MockMobile.Metadata();
            }

            return metadata;
        }

        /**
         * With the real iConnect library, this method will probably be passed
         * a byte or char array.  But for testing, we use Java serialization of
         * the metadata object.
         */
        public void writeMetadata(MockMobile.Metadata metadata)
            throws MockMobile.PluginException {

            procMetadata.setPluginMetadata(metadata);

            writeProcessorMetadata(null, procMetadata);

            this.metadata = metadata;
        }

        /**
         * Since there is only one database (RDBMS or collection of tables) per
         * JE Environment, only one instance of MockMobile.Database is needed.
         */
        class JEMobileDatabase implements MockMobile.Database {

            private Set<String> importAckPubNames = new HashSet<String>();
            private Transaction importTxn;
            private ChangeReader changeReader;
            private Iterator<ChangeTxn> changeTxnIter;
            private Collection<ChangeReader> allChangeReaders =
                new ArrayList<ChangeReader>();
            private long changeTxnCount;
            private long changeTxnAck;
            private final ImportedServerTxnsBinding binding = 
                new ImportedServerTxnsBinding();
            private final ExportConfig exportConfig;
            private final ImportConfig importConfig;

            public JEMobileDatabase(ExportConfig exportConfig, 
                                    ImportConfig importConfig) {
                super();
                this.exportConfig = exportConfig;
                this.importConfig = importConfig;
            }

            /**
             * Not currently called.  May be needed for queue support.
             * TODO FOR MARK
             */
            public void compose()
                throws MockMobile.PluginException {

                throw new UnsupportedOperationException();
            }
            
            /**
             * Not currently called.  May be needed for queue support.
             * TODO FOR MARK
             */
            public void apply()
                throws MockMobile.PluginException {
            }
            
            /**
             * TODO FOR MARK: support snapshot (JE Database) creation, when the
             * database is defined on the server but does not yet exist on the
             * client.
             */
            public void createSnapshot(String name,
                                       int pubId,
                                       int snapId,
                                       int flags,
                                       byte [] info)
                throws MockMobile.PluginException {

                throw new UnsupportedOperationException();
            }
                            
            /**
             * We do not intend to support snapshot deletion.
             */
            public void dropSnapshot(int snapId)
                throws MockMobile.PluginException {

                throw new UnsupportedOperationException();
            }
                    
            /**
             * Called to inform the plugin of state changes.
             */
            public void callback(int stage, Exception e)
                throws MockMobile.PluginException {

                if (stage == MockMobile.Plugin.SYNC_FINISHED) {

                    if (allChangeReaders.size() > 0) {

                        /*
                         * Ensure that the same number of export txns sent is
                         * the number ack'd.
                         */
                        if (changeTxnCount != changeTxnAck) {
                            /* TODO: use appropriate JE exception. */
                            throw new RuntimeException();
                        }

                        Durability durability = exportConfig.getDurability();
                        if (durability == null) {
                            durability = Durability.COMMIT_WRITE_NO_SYNC;
                        }

                        TransactionConfig txnConfig = new TransactionConfig();
                        txnConfig.setDurability(durability);
                        Transaction exportTxn =
                            getEnvironment().beginTransaction(null, txnConfig);

                        for (ChangeReader reader : allChangeReaders) {
                            reader.discardChanges(exportTxn);
                        }
                        exportTxn.commit();
                    }
                }
            }
                    
            /**
             * Called during sync initialization, to get the server
             * transactions that were previously imported successfully.
             * These are sent to the server to ack the transactions.
             */
            public MockMobile.Transaction[] getTransactions(int pubId,
                                                            int prio,
                                                            int type)
                throws MockMobile.PluginException {

                if (type == MockMobile.Transaction.TYPE_CLIENT) {
                    /* getNextReader will be called instead. */
                    throw new UnsupportedOperationException();
                }

                assert type == MockMobile.Transaction.TYPE_SERVER;

                /*
                 * Read import txn IDs from Sync DB, which were stored during
                 * the previous session.  Save pub names that are ack'd.
                 */
                String pubName = metadata.getPubById(pubId).name;
                assert pubName != null;
                importAckPubNames.add(pubName);
                DatabaseEntry data = new DatabaseEntry();
                readProcessorTxnData(null, pubName, data);
                
                if (data.getData() == null) {
                    return null;
                }

                ImportedServerTxns txns = binding.entryToObject(data);
                ArrayList<MockMobile.Transaction> list = 
                    new ArrayList<MockMobile.Transaction>();
                for (MockMobile.Transaction txn : txns.getImportTxns()) {
                    if ((txn.prio == prio) && (txn.type == type)) {
                        list.add(txn);
                    }
                }
                
                return list.toArray(new MockMobile.Transaction[] {});
            }
            
            /**
             * Called when a server/import txn is applied on client, but not
             * yet ack'd. Client must save the txn ID persistently and commit
             * the txn.  The txn ID must be returned when
             * getTransactions(TYPE_SERVER) is called during the next session.
             * During that next session, prior to committing any new import
             * txns, the client must delete the record of the prior txn IDs
             * persistently.
             */
            public void addTransaction(MockMobile.Transaction t)
                throws MockMobile.PluginException {

                assert importTxn != null;
                
                /*
                 * Before committing the first import txn, clear any previously
                 * ack'd txn IDs.
                 */
                if (importAckPubNames.size() > 0) {
                    DatabaseEntry data = new DatabaseEntry(new byte[0]);
                    for (String pubName : importAckPubNames) {
                        writeProcessorTxnData(importTxn, pubName, data);
                    }
                    importAckPubNames.clear();
                }

                /*
                 * Ready to commit import txn.  Update persistent set of import
                 * txn IDs as part of the same import txn, to ensure that will
                 * will ack this txn ID in the next session.
                 */
                String pubName = metadata.getPubById(t.pubId).name;
                assert pubName != null;

                /* Add a new MockMobile.Transaction. */
                DatabaseEntry data = new DatabaseEntry();
                readProcessorTxnData(importTxn, pubName, data);
                ImportedServerTxns txns = (data.getSize() == 0) ? 
                    new ImportedServerTxns() : binding.entryToObject(data);
                txns.addImportedTransaction(t);
                binding.objectToEntry(txns, data);

                writeProcessorTxnData(importTxn, pubName, data);
                importTxn.commit();
                importTxn = null;
            }
            
            /**
             * Called when a client/export txn is ack'd by the server.  The
             * client must delete it persistently from the tracked changes when
             * the sync is complete (perhaps it can be deleted earlier, but we
             * don't know when that might be).
             */
            public void removeTransaction(long trId)
                throws MockMobile.PluginException {

                /*
                 * We don't verify each ack'd export transaction by ID, we only
                 * check that the sent and ack'd counts match.  If there are
                 * Mobile Server error conditions that require us to check
                 * every txn ID, we'll deal with that later.
                 */
                changeTxnAck += 1;
            }
            
            /**
             * This is probably needed for coordinating transaction ID
             * assignment, but the details are not clear.  TODO FOR MARK
             */
            public long getPubTrSeq(int pubId)
                throws MockMobile.PluginException {

                throw new UnsupportedOperationException();
            }
            
            /**
             * This is probably needed for coordinating transaction ID
             * assignment, but the details are not clear.  TODO FOR MARK
             */
            public void setPubTrSeq(int pubId, long trSeq)
                throws MockMobile.PluginException {

                throw new UnsupportedOperationException();
            }
            
            /**
             * This method will not be called, and openNextReader will be
             * called instead.
             */
            public MockMobile.SnapshotReader openReader(long trId, int snapId)
                throws MockMobile.PluginException {

                throw new UnsupportedOperationException();
            }
            
            /**
             * Called to open a SnapshotWriter that will be used to write the
             * records in a single snapshot, for an import transaction.
             */
            public MockMobile.SnapshotWriter openWriter(long trId, int snapId)
                throws MockMobile.PluginException {

                if (importTxn == null) {
                    Durability durability = importConfig.getDurability();
                    if (durability == null) {
                        durability = Durability.COMMIT_WRITE_NO_SYNC;
                    }

                    TransactionConfig txnConfig = new TransactionConfig();
                    txnConfig.setDurability(durability);
                    importTxn = 
                        getEnvironment().beginTransaction(null, txnConfig);
                }

                String snapName = metadata.getSnapById(snapId).name;

                return new JESnapshotWriter(importTxn, snapName);
            }

            /**
             * Returns a reader for the next export transaction.
             *
             * For export transactions, Yev has agreed to add this method so
             * that we don't have to read all txn IDs in a separate pass in JE.
             * For JE, iConnect will call this method instead of
             * getTransactions(TYPE_CLIENT) and the openReader method above.
             *
             * Also, for JE's sake, the returned SnapshotReader will actually
             * read records for more than one snapshot, and the Record.snapId
             * identifies the snapshot.  That way, JE does not have to group
             * records by snapshot.
             *
             * @param txn is filled in by this method with the next
             * transaction, when a non-null reader is returned.
             *
             * @return a SnapshotReader for reading the records in the
             * transaction, or null if there are no more transactions.
             */
            public MockMobile.SnapshotReader openNextReader
                (int pubId, int prio, MockMobile.Transaction txn)
                throws MockMobile.PluginException {

                String pubName = metadata.getPubById(pubId).name;
                assert pubName != null;

                if (changeReader == null) {
                    /* TODO: use config settings for txn consolidation. */
                    changeReader = openChangeReader
                        (pubName, false /*consolidateTransactions*/,
                         0 /*consolidateMaxMemory*/);
                    changeTxnIter = changeReader.getChangeTxns();
                }

                if (!changeTxnIter.hasNext()) {
                    allChangeReaders.add(changeReader);
                    changeReader = null;
                    changeTxnIter = null;
                    return null;
                }

                ChangeTxn changeTxn = changeTxnIter.next();
                assert pubName.equals(changeTxn.getDataSetName());

                txn.trId = changeTxn.getTransactionId();
                txn.pubId = pubId;
                txn.prio = MockMobile.Transaction.PRIO_DEFAULT;
                txn.type = MockMobile.Transaction.TYPE_CLIENT;

                MockMobile.SnapshotReader reader = 
                    new JESnapshotReader(changeTxn);
                changeTxnCount += 1;
                return reader;
            }
            
            public void close()
                throws MockMobile.PluginException {
            }
        }
    }

    /**
     * For testing, this utility method converts from an array of primitive
     * fields stored in an SQL table (JDBC data types are implied), to an
     * array of bytes that is to be transferred to Mobile Server.
     *
     * When we use the real iConnect library instead of the MockMobile classes,
     * we'll copy or use utilities in iConnect that do this conversion.  For
     * testing here, we'll simply use Java serialization.
     *
     * The RecordConverter is responsible for converting from a JE record
     * key/data to the fields array.
     */
    static byte[] sqlFieldsToBytes(Object[] fields) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            for (Object obj : fields) {
                oos.writeObject(obj);
            }
        } catch (IOException e) {
            /* Do nothing, just print it out. */
            e.printStackTrace();
        }

        return baos.toByteArray();
    }

    /**
     * Inverse of sqlFieldsToBytes, use Java Serialization.
     */
    static Object[] bytesToSqlFields(byte[] bytes) {
        ArrayList list = new ArrayList();
        ByteArrayInputStream bais = 
            new ByteArrayInputStream(bytes, 0, bytes.length);
        try {
            ObjectInputStream ois = new ObjectInputStream(bais);
            while (true) {
                try {
                    Object obj = ois.readObject();
                    list.add(obj);
                } catch (EOFException e) {
                    /* Break the loop while reach the end of the stream. */
                    break;
                }                
            }
        } catch (ClassNotFoundException e) {
            /* Do nothing, just print it out. */
            e.printStackTrace();
        } catch (IOException e) {
            /* Do nothing, just print it out. */
            e.printStackTrace();
        }

        return list.toArray(new Object[] {});
    }

    /* The format that imported server transactions saved in the SyncDB. */
    class ImportedServerTxns { 
        private ArrayList<MockMobile.Transaction> importTxns = 
            new ArrayList<MockMobile.Transaction>();

        public void addImportedTransaction(MockMobile.Transaction importTxn) {
            importTxns.add(importTxn);
        }

        public ArrayList<MockMobile.Transaction> getImportTxns() {
            return importTxns;
        }
    }

    /* Binding for read/write the imported server transactions from SyncDB. */
    class ImportedServerTxnsBinding extends 
        TupleBinding<ImportedServerTxns> {

        @Override
        public ImportedServerTxns entryToObject(TupleInput input) {
            ImportedServerTxns txns = new ImportedServerTxns();

            int size = input.readInt();
            for (int i = 0; i < size; i++) {
                MockMobile.Transaction transaction = 
                    new MockMobile.Transaction();
                transaction.trId = input.readLong();
                transaction.pubId = input.readInt();
                transaction.prio = input.readInt();
                transaction.type = input.readInt();
                txns.addImportedTransaction(transaction);
            }

            return txns;
        }

        @Override
        public void objectToEntry(ImportedServerTxns serverTxns,
                                  TupleOutput output) {
            int size = serverTxns.getImportTxns().size();
            output.writeInt(size);

            for (MockMobile.Transaction importTxn : 
                 serverTxns.getImportTxns()) {
                output.writeLong(importTxn.trId);
                output.writeInt(importTxn.pubId);
                output.writeInt(importTxn.prio);
                output.writeInt(importTxn.type);
           }
        }
    }

    /* 
     * A test SnapshotReader which reads the key/data pairs from the ChangeReader,
     * and use the RecordConverter translates the key/data pairs to JDBC objects, 
     * then use sqlFieldsToBytes to convert the objects to byte array, use the
     * byte array fill the MockMobile.Record.buf.
     */
    class JESnapshotReader implements MockMobile.SnapshotReader {
        /* ChangeTxn read from the ChangeReader. */
        private final ChangeTxn changeTxn;
        /* Mapping from the name to the RecordConverter for a SyncDatabase. */
        private final Map<String, RecordConverter> converters = 
            new HashMap<String, RecordConverter>();
        private final Iterator<Change> changes;

        public JESnapshotReader(ChangeTxn changeTxn) {
            this.changeTxn = changeTxn;
            this.changes = changeTxn.getOperations();

            /* Get the RecordConverter for each SyncDatabase. */
            Collection<SyncDatabase> syncDbs = 
                getDataSets().get(changeTxn.getDataSetName()).getDatabases();
            for (SyncDatabase database : syncDbs) {
                converters.put
                    (database.getLocalName(), database.getConverter());
            }
        }

        public boolean read(MockMobile.Record r) 
            throws MockMobile.PluginException {

            boolean hasNext = changes.hasNext();

            if (hasNext) {
                Change operation = changes.next();
                RecordConverter converter = 
                    converters.get(operation.getDatabaseName());
                DatabaseEntry key = operation.getKey();
                DatabaseEntry data = operation.getData();
                ChangeType type = operation.getType();

                switch (type) {
                    case INSERT:
                        r.type = 0;
                        break;
                    case UPDATE:
                        r.type = 1;
                        break;
                    case DELETE:
                        r.type = 2;
                        break;
                    default:
                        throw new IllegalArgumentException();
                }
                Object[] fields = 
                    new Object[converter.getExternalFieldTypes().length];
                converter.convertLocalToExternal(key, data, fields);
                r.buf = sqlFieldsToBytes(fields);
            }

            return hasNext;
        }

        /* Don't know what to do in close(). */
        public void close() 
            throws MockMobile.PluginException {
        }
    }

    /* 
     * Implments a test SnapshotWriter, it applies the records in the imported
     * server transactions. 
     *
     * It will first translate the byte array of Record.buf to JDBC objects, 
     * and use the RecordConverter to convert the JDBC objects to key/data 
     * pairs.
     */
    class JESnapshotWriter implements MockMobile.SnapshotWriter {
        private RecordConverter converter;
        private String localDbName;
        private Database db;
        private final Transaction importTxn;

        public JESnapshotWriter(Transaction importTxn, String snapName) {
            this.importTxn = importTxn;

            /* Find out the local database name. */
            for (SyncDataSet dataSet : getDataSets().values()) {
                for (SyncDatabase database : dataSet.getDatabases()) {
                    if (snapName.equals(database.getExternalName())) {
                        this.converter = database.getConverter();
                        this.localDbName = database.getLocalName();
                        break;
                    }
                }
            }

            /* If the local database doesn't exist. */
            if (converter == null || localDbName == null) {
                throw new IllegalArgumentException();
            }

            /* Open the corresponding local database in JE Environment. */
            DatabaseConfig dbConfig = new DatabaseConfig();
            dbConfig.setTransactional(true);
            dbConfig.setUseExistingConfig(true);

            db = env.openDatabase(null, localDbName, dbConfig);
        }

        public boolean write(MockMobile.Record r) 
            throws MockMobile.PluginException {

            /* Use bytesToSqlFields to convert byte array to objects. */
            Object[] objects = bytesToSqlFields(r.buf);

            /* Use RecordConverter to convert objects to DatabaseEntry. */
            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry data = new DatabaseEntry();
            converter.convertExternalToLocal(objects, key, data);

            /* Write the records, assume the database is not duplicated now. */
            OperationStatus status = null;
            if (r.type == MockMobile.Record.INSERT ||
                r.type == MockMobile.Record.UPDATE) {
                status = db.put(importTxn, key, data);
            } else if (r.type == MockMobile.Record.DELETE) {
                status = db.delete(importTxn, key);
            }

            if (status == OperationStatus.SUCCESS) {
                return true;
            } else if (status == OperationStatus.NOTFOUND && 
                       r.type == MockMobile.Record.DELETE) {

                /* 
                 * Because the database operations are random, so a delete 
                 * operation may delete a record that has already been deleted.
                 */
                return true;
            }

            return false;
        }

        /* Truncate the contents in this database. */
        public void truncate()
            throws MockMobile.PluginException {

            throw new UnsupportedOperationException
                ("JE Data sync doesn't support imported truncate operation.");
        }

        public void close()
            throws MockMobile.PluginException {

            db.close();
        }
    }

    /* ProcessorMetadata used for this test. */
    static class MyProcessorMetadata extends ProcessorMetadata<MySyncDataSet> {
        MockMobile.Metadata pluginMetadata;
        MobileConnectionConfig configMetadata;

        public void setConfigMetadata(MobileConnectionConfig configMetadata) {
            this.configMetadata = configMetadata;
        }

        public void setPluginMetadata(MockMobile.Metadata pluginMetadata) {
            this.pluginMetadata = pluginMetadata;
        }

        public MobileConnectionConfig getConfigMetadata() {
            return configMetadata;
        }

        public MockMobile.Metadata getPluginMetadata() {
            return pluginMetadata;
        }
    }

    /* SyncDataSet used in this test. */
    static class MySyncDataSet extends SyncDataSet {
        public MySyncDataSet(String dataSetName,
                             SyncProcessor processor,
                             Collection<SyncDatabase> databases) {
            super(dataSetName, processor, databases);
        }
    }
}
