/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.sync.test;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Mock Mobile Client Library API
 *
 * Because we cannot currently test with the actual Java client library
 * (iConnect) for Mobile Server, we use these Mock library classes to simulate
 * the iConnect library.
 *
 * When we integrate with the real iConnect library in the future and implement
 * the MobileSyncProcessor class, all the MockMobile.* classes and interfaces
 * defined here will be replaced with the real version of these classes, and
 * we can test with the real Mobile Server.
 *
 * See http://en.wikipedia.org/wiki/Mock_object
 *
 * This class should not any JE APIs, since it is intended to represent the
 * external iConnect library.
 */
class MockMobile {

    /**
     * A mock version of the oracle.opensync.osp.Plugin interface in the
     * iConnect library.  This interface is implemented by all iConnect
     * plugins, and it must be implemented by the JE plugin.
     */
    interface Plugin {

        int SYNC_STARTED        = 0;

        int BEFORE_SEND         = 1;

        int AFTER_SEND          = 2;

        int BEFORE_RECEIVE      = 3;
        
        int AFTER_RECEIVE       = 4;
        
        int SYNC_FINISHED       = 5;
        
        int COMPOSE_STARTED     = 6;
        
        int COMPOSE_FINISHED    = 7;
        
        int APPLY_STARTED       = 8;
                        
        int APPLY_FINISHED      = 9;

        void init(String user, char [] pwd) throws PluginException;
                
        void close() throws PluginException;

        boolean supportsBlobs();
        
        boolean supportsQueues();
        
        String defaultDBName() throws PluginException;
        
        void setDebug(boolean toDebug) throws PluginException;

        void setBackground(boolean isBackground) throws PluginException;

        void createDatabase(String name, byte [] info) throws PluginException;

        Database openDatabase(String name) throws PluginException;

        /*
         * New APIs to be added for JE, based on conversation with Yev.
         * Normally iConnect stores metadata in an XML file, but for JE it must
         * be stored in a database, so it will be replicated.
         */
        Metadata readMetadata() throws PluginException;
        void writeMetadata(Metadata metadata) throws PluginException;
    }

    /**
     * A mock version of the oracle.opensync.osp.Database interface in the
     * iConnect library.  Implemented by Plugins.
     *
     * Note that in iConnect terminology, a "database" is a collection of
     * snapshots (which for SQLite are tables, but for JE are
     * com.sleepycat.je.Databases).
     */
    interface Database {             
        
        //snapshot creation options     
        int SNAP_READONLY = 0x1;
        
        void compose() throws PluginException;
        
        void apply() throws PluginException;
        
        void createSnapshot(String name, int pubId, int snapId,
                int flags, byte [] info) throws PluginException;
                        
        void dropSnapshot(int snapId) throws PluginException;
                
        void callback(int stage, Exception e) throws PluginException;
                
        /**
         * Called during sync initialization, to get the server transactions
         * that were previously imported successfully.
         */
        Transaction [] getTransactions(int pubId, int prio, int type)
                throws PluginException;
        
        /**
         * Called when a server/import txn is applied on client, but not
         * yet ack'd. Client must save the txn ID persistently and commit
         * the txn.  The txn ID must be returned when
         * getTransactions(TYPE_SERVER) is called during the next session.
         * During that next session, prior to committing any new import
         * txns, the client must delete the record of the prior txn IDs
         * persistently.
         */
        void addTransaction(Transaction t) throws PluginException;
        
        /**
         * Called when a client/export txn is ack'd by the server.  The client
         * must delete it persistently from the tracked changes when the sync
         * is complete (perhaps it can be deleted earlier, but we don't know
         * when that might be).
         */
        void removeTransaction(long trId) throws PluginException;
        
        long getPubTrSeq(int pubId) throws PluginException;
        
        void setPubTrSeq(int pubId, long trSeq) throws PluginException;
        
        SnapshotReader openReader(long trId, int snapId)
            throws PluginException;
        
        SnapshotWriter openWriter(long trId, int snapId)
            throws PluginException;

        /**
         * Returns a reader for the next export transaction.
         *
         * For export transactions, Yev has agreed to add this method so that
         * we don't have to read all txn IDs in a separate pass in JE.  For JE,
         * iConnect will call this method instead of
         * getTransactions(TYPE_CLIENT) and the openReader method above.
         *
         * Also, for JE's sake, the returned SnapshotReader will actually read
         * records for more than one snapshot, and the Record.snapId identifies
         * the snapshot.  That way, JE does not have to group records by
         * snapshot.
         *
         * @param txn is filled in by this method with the next transaction,
         * when a non-null reader is returned.
         *
         * @return a SnapshotReader for reading the records in the transaction,
         * or null if there are no more transactions.
         */
        SnapshotReader openNextReader(int pubId, int prio, Transaction txn)
            throws PluginException; 
        
        void close() throws PluginException;
    }

    /**
     * A mock version of the oracle.opensync.osp.Transaction class in the
     * iConnect library.  Used by Plugins.
     */
    static class Transaction {
        
        public static final int ID_NONE =      -1;
        
        public static final int PRIO_HIGH =     0;
        
        public static final int PRIO_DEFAULT =  1;
        
        public static final int PRIO_LOWEST = 255;
                
        public static final int TYPE_CLIENT =   0;
        
        public static final int TYPE_SERVER =   1;
        
        public long trId;
        
        public int pubId;
        
        public int prio;
        
        public int type;
        
        /* I think this field can be ignored by JE. */
        public int [] snapIds;
        
    }       

    /**
     * A mock version of the oracle.opensync.osp.SnapshotReader interface in
     * the iConnect library.  Implemented by Plugins.
     */
    interface SnapshotReader {
        
        boolean read(Record r) throws PluginException;
        
        void close() throws PluginException;
        
    }       

    /**
     * A mock version of the oracle.opensync.osp.SnapshotWriter interface in
     * the iConnect library.  Implemented by Plugins.
     */
    interface SnapshotWriter {
        
        boolean write(Record r) throws PluginException;
        
        void truncate() throws PluginException;
        
        void close() throws PluginException;
        
    }       

    /**
     * A mock version of the oracle.opensync.osp.Record class in the iConnect
     * library.  Used by Plugins.
     */
    static class Record {
        
        public static final int INSERT = 0;
        
        public static final int UPDATE = 1;
        
        public static final int DELETE = 2;
        
        public int type;
        
        public long svrVer;
        
        public long cliVer;
        
        public byte [] buf;
        
        public int length;
        
        public Blob [] blobs;
        
        public int blobCnt;

        /*
         * Yev has agreed to add this field for JE, so that SnapshotReader
         */
        public int snapId;
    }

    /**
     * A mock version of the oracle.opensync.osp.Blob interface in the iConnect
     * library.  Implemented by Plugins.
     */
    interface Blob {

        OutputStream getOutputStream() throws PluginException;
        
        InputStream getInputStream() throws PluginException;
        
        long size() throws PluginException;
    }

    /**
     * A mock version of the oracle.opensync.osp.PluginException class in the
     * iConnect library.  Thrown by Plugins.
     */
    static class PluginException extends Exception {
            
        public PluginException() {
        }
                
        public PluginException(String msg) {               
            super(msg);
        }       
                        
        public PluginException(String msg, Throwable cause) {               
            super(msg, cause);
        }
    }

    /**
     * New class to be added for JE, based on conversation with Yev.  Normally
     * iConnect stores metadata in an XML file, but for JE it must be stored in
     * a database, so it will be replicated.
     *
     * The metadata structure here is oversimplified for testing purposes.  But
     * since this new API does not exist yet, there is no way to predict
     * exactly what it will look like anyway.  In other words, this metadata
     * structure will have to change in the future, no matter what.
     */
    static class Metadata implements Serializable {
        final Set<Publication> pubs = new HashSet<Publication>();

        /* Add a publication to this Metadata. */
        public void addPublication(Publication pub) {
            pubs.add(pub);
        }

        /* Implements Serializable so that we can use Java Serialization. */
        static class Publication implements Serializable {
            final int id;
            final String name;
            final Set<Snapshot> snaps = new HashSet<Snapshot>();

            Publication(int id, String name) {
                this.id = id;
                this.name = name;
            }

            /* Add a new snapshot to the publication. */
            void addSnapshot(Snapshot snapshot) {
                snaps.add(snapshot);
            }

            Snapshot getSnapByName(String name) {
                for (Snapshot snap : snaps) {
                    if (name.equals(snap.name)) {
                        return snap;
                    }
                }
                return null;
            }
        }

        /* Implements Serializable so that we can use Java Serialization. */
        static class Snapshot implements Serializable {
            final int id;
            final String name;
            final Publication pub;

            Snapshot(int id, String name, Publication pub) {
                this.id = id;
                this.name = name;
                this.pub = pub;
            }
        }

        Publication getPubByName(String name) {
            for (Publication pub : pubs) {
                if (name.equals(pub.name)) {
                    return pub;
                }
            }
            return null;
        }

        Publication getPubById(int id) {
            for (Publication pub : pubs) {
                if (id == pub.id) {
                    return pub;
                }
            }
            return null;
        }

        Snapshot getSnapById(int id) {
            for (Publication pub : pubs) {
                for (Snapshot snap : pub.snaps) {
                    if (id == snap.id) {
                        return snap;
                    }
                }
            }
            return null;
        }
    }

    /**
     * A mock version of the oracle.opensync.ose.OSESession class in the
     * iConnect library.
     *
     * The OSESession class is the public API for the iConnect library.
     * However, in the JE API for Mobile Sync, we wrap the OSESession class in
     * our own JE-specific API (MobileSyncProcessor), for consistency with
     * other JE APIs and so the user can find out everything they need to know
     * about JE Mobile Sync in the JE javadoc -- the user does not have to read
     * the iConnect javadoc or call the OSESession API directly.
     */
    static class OSESession {

        private Plugin plugin;
        private boolean forceRefresh;
        private final Set<String> selectPubs = new HashSet<String>();
        private final Map<String, Set<Long>> exportTxns = 
            new HashMap<String, Set<Long>>();
        private Collection<Metadata.Publication> pubs;
        private Database database;
        private Metadata metadata;
        private final ServerData data = new ServerData();
        private int exportedRecords;

        public OSESession(String user, char[] password) {
            /* User name and password not used for testing. */
        }

        public void setURL(String url) {
            /* URL not used for testing. */
        }

        public void setForceRefresh(boolean forceRefresh) {
            this.forceRefresh = forceRefresh;
        }

        /**
         * This method is not part of the real OSESession class, because
         * currently all plugin classes are hardcoded in the iConnect library,
         * and a plugin is associated with each snapshot on the client by a
         * different mechanism.  For testing, this simple method is used to set
         * the JE plugin to be used for all snapshots.
         */
        public void setPlugin(Plugin plugin) {
            this.plugin = plugin;
        }

        /**
         * Add publication name to be sync'd.  If never called, all known
         * publications are sync'd.
         */
        public void selectPub(String name) throws OSEException {
            selectPubs.add(name);
        }

        public void sync() throws OSEException {

            try {
                /* Initialize the plugin. */
                plugin.init("dummyUser", "dummyPassword".toCharArray());

                /* For JE, there is only one database: the default database. */
                database = plugin.openDatabase(plugin.defaultDBName());
                database.callback(Plugin.SYNC_STARTED, null);

                /* Get the last stored metadata. */
                metadata = plugin.readMetadata();

                /* Identify publications to sync. */
                if (selectPubs.size() == 0) {
                    pubs = metadata.pubs;
                } else {
                    pubs = new ArrayList<Metadata.Publication>();
                    for (String name : selectPubs) {
                        Metadata.Publication pub = metadata.getPubByName(name);
                        if (pub == null) {
                            throw new OSEException
                                ("Publication not found: " + name);
                        }
                        pubs.add(pub);
                    }
                }

                /* Perform sync operations. */
                ackImportTransactions();
                database.callback(Plugin.BEFORE_SEND, null);
                sendExportTransactions();
                database.callback(Plugin.AFTER_SEND, null);
                database.callback(Plugin.BEFORE_RECEIVE, null);
                ackExportTransactions();
                receiveImportTransactions();
                database.callback(Plugin.AFTER_RECEIVE, null);
                database.callback(Plugin.SYNC_FINISHED, null);

                plugin.close();
            } catch (PluginException e) {
                throw new OSEException(e);
            }
        }

        /**
         * For each publication, ack the server transactions that were
         * previously imported.
         */
        private void ackImportTransactions()
            throws OSEException, PluginException {

            for (Metadata.Publication pub : pubs) {

                Transaction[] txns = database.getTransactions
                    (pub.id, Transaction.PRIO_DEFAULT,
                     Transaction.TYPE_SERVER);

                /* 
                 * If there are no previously imported server transactions, do 
                 * nothing and return. 
                 */
                if (txns == null) {
                    return;
                }

                for (int i = 0; i < txns.length; i += 1) {
                    /* Assume the server responds to all acks. */
                    data.removeImportTransaction(txns[i].trId);
                }
            }
        }

        /**
         * For each publication, send the client transactions that are being
         * exported.
         */
        private void sendExportTransactions()
            throws OSEException, PluginException {

            final Transaction txn = new Transaction();
            final Record rec = new Record();

            for (Metadata.Publication pub : pubs) {
                Set<Long> txnIds = new HashSet<Long>();
                while (true) {
                    final SnapshotReader reader = database.openNextReader
                        (pub.id, Transaction.PRIO_DEFAULT, txn);
                    if (reader == null) {
                        break;
                    }
                    while (reader.read(rec)) {
                        /* Assume it is applied on the server successfully. */
                        exportedRecords++;
                        /* TODO FOR MARK: Add Blob handling. */
                    }
                    txnIds.add(txn.trId);
                    reader.close();
                }
                exportTxns.put(pub.name, txnIds);
            }
        }

        /**
         * Inform the client that it may remove the export transactions from
         * the change set.
         */
        private void ackExportTransactions()
            throws OSEException, PluginException {

            for (Metadata.Publication pub : pubs) {
                Set<Long> txnIds = exportTxns.get(pub.name);
                for (long txnId : txnIds) {
                    database.removeTransaction(txnId);
                }
            }
        }

        /**
         * For each publication, receive the client transactions that are being
         * imported.
         */
        private void receiveImportTransactions()
            throws OSEException, PluginException {

            for (Metadata.Publication pub : pubs) {
                int pubId = pub.id;
                for (long txnId : data.pendingImportTransactions(pubId)) {
                    for (int snapId : data.pendingImportSnapshots(txnId)) {
                        SnapshotWriter writer =
                            database.openWriter(txnId, snapId);
                        for (Record rec :
                             data.pendingImportRecords(txnId, snapId)) {
                            writer.write(rec);
                        }
                        writer.close();
                    }
                    Transaction txn = new Transaction();
                    txn.trId = txnId;
                    txn.pubId = pubId;
                    txn.prio = Transaction.PRIO_DEFAULT;
                    txn.type = Transaction.TYPE_SERVER;
                    database.addTransaction(txn);
                }
            }
        }

        /**
         * If cancel is successful, the sync thread throws OSEException
         * with error code OSEExceptionConstants.SYNC_CANCELED 
         */
        public void cancelSync() throws OSEException {
            /* TODO: set a flag which will cause the OSEException. */
        }

        public int getExportedRecords() {
            return exportedRecords;
        }
    }

    /**
     * A mock version of the oracle.opensync.ose.OSEException class in the
     * iConnect library.  Thrown by OSESession.
     *
     * TODO FOR MARK: Add error codes (these are already defined in the
     * iConnect library) to the exception, so they can be interpretted by JE.
     * JE may wish to handle a specific exception in a particular way, or wrap
     * it in a specific JE exception.
     */
    static class OSEException extends Exception {
            
        public OSEException() {
        }
                
        public OSEException(String msg) {               
            super(msg);
        }       
                        
        public OSEException(String msg, Throwable cause) {               
            super(msg, cause);
        }
                        
        public OSEException(Throwable cause) {               
            super(cause);
        }
    }

    /**
     * For testing, we need to simulate read and write transactions on the
     * server.  With the real server, data is stored by Oracle Mobile Server,
     * and an HTTP protocol is used to perform transactions.  In this mock
     * server connection, data is stored in memory.  Since server data is not
     * persistent, test data disappears when the Java process exits.
     */
    private static class ServerData {
        private final ArrayList<ServerChangeTxn> serverTxns = 
            new ArrayList<ServerChangeTxn>();

        public ServerData() {
            initTestData();
        }

        /* 
         * Create some imported data.
         *
         * Note: this data initialization corresponds to the record coposition
         * we've made in MockMobileTest and the Metadata we've made in 
         * TestMobileSyncProcessor. Each record on the server has two columns, 
         * one is the key, the other is the data.
         *
         * For the sake of simplicity, the first publication will make changes
         * on the first 20 entries of dbA (see MockMobileTest.java), the second
         * publication will make changes on the entries between 21 and 40 of 
         * dbB (see MockMobileTest.java).
         */
        private void initTestData() {
            Random random = new Random();
            for (int i = 1; i <= 2; i++) {
                for (long j = 1 + 20 * (i - 1); j <= 20 * i; j++) {
                    ServerChangeTxn serverTxn = new ServerChangeTxn(i, j);
                    serverTxn.addSnap(i);

                    for (int m = 1; m <= 5; m++) {
                        Record record = new Record();
                        record.type = random.nextInt(3);

                        int key = (int) j;
                        String name = new String();
                        for (int n = 1; n <= random.nextInt(30); n++) {
                            name += "a";
                        }
                        record.snapId = i;
                        record.buf = TestMobileSyncProcessor.sqlFieldsToBytes
                            (new Object[] {new Integer(key), name});
                        serverTxn.addRecord(record);
                    }
                    serverTxns.add(serverTxn);
                }
            }
        }

        /**
         * Returns IDs of server transactions that are to be imported.
         */
        Collection<Long> pendingImportTransactions(int pubId) {
            ArrayList<Long> list = new ArrayList<Long>();
            for (ServerChangeTxn txn : serverTxns) {
                
                if (txn.getPubId() == pubId) {
                    list.add(txn.getTxnId());
                }
            }

            return list;
        }

        /**
         * Returns IDs of snapshots, for a given server transaction, that are
         * to be imported.
         */
        Collection<Integer> pendingImportSnapshots(long txnId) {
            for (ServerChangeTxn txn : serverTxns) {
                if (txn.getTxnId() == txnId) {
                    return txn.getSnapIds();
                }
            }

            return null;
        }

        /**
         * Returns records, for a given server transaction and snapshot, that
         * are to be imported.
         */
        Collection<Record> pendingImportRecords(long txnId, int snapId) {
            ArrayList<Record> list = new ArrayList<Record>();
            for (ServerChangeTxn txn : serverTxns) {
               if (txn.getTxnId() == txnId) {
                   for (Record record : txn.getRecords()) {
                       if (record.snapId == snapId) {
                           list.add(record);
                       }
                   }
                   break;
               }
            }

            return list;
        }

        /**
         * Removes a previously imported transaction from the server, when it
         * has been acknowledged by JE.
         */
        void removeImportTransaction(long txnId) {
            int index = 0;
            for (ServerChangeTxn txn : serverTxns) {
                if (txn.getTxnId() == txnId) {
                    break;
                }
                index++;
            }

            serverTxns.remove(index);
        }
    }

    /* Class used to present an imported server side transaction. */
    static class ServerChangeTxn {
        private final int pubId;
        private final long serverTxnId;
        /* Snaps that appears in this transaction. */
        private final ArrayList<Integer> snapIds = new ArrayList<Integer>();
        /* Changes that need to be imported on the client. */
        private final ArrayList<Record> records = new ArrayList<Record>();

        public ServerChangeTxn(int pubId, long serverTxnId) {
            this.pubId = pubId;
            this.serverTxnId = serverTxnId;
        }

        public void addSnap(int snapId) {
            if (!snapIds.contains(snapId)) {
                snapIds.add(snapId);
            }
        }

        public void addRecord(Record record) {
            records.add(record);
        }

        public ArrayList<Integer> getSnapIds() {
            return snapIds;
        }

        public ArrayList<Record> getRecords() {
            return records;
        }

        public long getTxnId() {
            return serverTxnId;
        }

        public int getPubId() {
            return pubId;
        }
    }
}
