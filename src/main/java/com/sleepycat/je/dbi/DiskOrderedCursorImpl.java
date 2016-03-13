/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.dbi;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DiskOrderedCursorConfig;
import com.sleepycat.je.DiskOrderedCursorProducerException;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.ThreadInterruptedException;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.tree.LN;

/**
 * This class implements the DiskOrderedCursor. When an instance is
 * constructed, a Producer Thread is created which runs a DiskOrderedScanner
 * against the DiskOrderedCursor's Database.  The callback for the
 * DiskOrderedScanner takes key/data byte arrays that are passed to it, and
 * then place those entries on a BlockingQueue which is shared between the
 * Producer Thread and the application thread.  When the application calls
 * getNext(), it simply takes an entry off the queue and hands it to the
 * caller.  The entries on the queue are simple KeyAndData structs which hold
 * byte[]'s for the key (and optional) data.  A special instance of KeyAndData
 * is used to indicate that the cursor scan has finished.
 *
 * The consistency guarantees are documented in the public javadoc for
 * DiskOrderedCursor, and are based on the use of DiskOrderedScanner (see its
 * javadoc for details).
 *
 * If the cleaner is operating concurrently with the DiskOrderedScanner, then
 * it is possible for a file to be deleted and a not-yet-processed LSN (i.e.
 * one which has not yet been returned to the user) might be pointing to that
 * deleted file.  Therefore, we must disable file deletion (but not cleaner
 * operation) during the DOS.
 */
public class DiskOrderedCursorImpl {

    /*
     * Simple struct to hold key and data byte arrays being passed through the
     * queue.
     */
    private class KeyAndData {
        final byte[] key;
        final byte[] data;

        /**
         * Creates a marker instance, for END_OF_QUEUE.
         */
        private KeyAndData() {
            this.key = null;
            this.data = null;
        }

        private KeyAndData(byte[] key, byte[] data) {
            this.key = key;
            this.data = data;
        }

        private byte[] getKey() {
            return key;
        }

        private byte[] getData() {
            return data;
        }
    }

    /*
     * The maximum number of entries that the BlockingQueue will store before
     * blocking the producer thread.
     */
    private int queueSize = 1000;

    /* Queue.offer() timeout in msec. */
    private int offerTimeout;

    /* The special KeyAndData which marks the end of the operation. */
    private final KeyAndData END_OF_QUEUE = new KeyAndData();

    private final Processor processor;
    private final DiskOrderedScanner scanner;
    private final BlockingQueue<KeyAndData> queue;
    private final Thread producer;
    private final DatabaseImpl dbImpl;
    private final boolean dups;
    private final boolean keysOnly;
    private final RuntimeException SHUTDOWN_REQUESTED_EXCEPTION =
        new RuntimeException("Producer Thread shutdown requested");

    /* DiskOrderedCursors are initialized as soon as they are created. */
    private boolean closed = false;

    private KeyAndData currentNode = null;

    public DiskOrderedCursorImpl(final DatabaseImpl dbImpl,
                                 final DiskOrderedCursorConfig config) {

        this.dbImpl = dbImpl;
        this.dups = dbImpl.getSortedDuplicates();

        DbConfigManager configMgr =
            dbImpl.getDbEnvironment().getConfigManager();

        this.offerTimeout = configMgr.getDuration
            (EnvironmentParams.DOS_PRODUCER_QUEUE_TIMEOUT);

        this.keysOnly = config.getKeysOnly();
        this.queueSize = config.getQueueSize();
        final long rootLSN = dbImpl.getTree().getRootLsn();
        this.processor = new Processor();
        this.scanner = new DiskOrderedScanner(dbImpl, processor, keysOnly,
                                              config.getLSNBatchSize(),
                                              config.getInternalMemoryLimit());
        this.queue = new ArrayBlockingQueue<KeyAndData>(queueSize);
        this.producer = new Thread() {
                public void run() {
                    try {
                        /* Prevent files from being deleted during scan. */
                        dbImpl.getDbEnvironment().getCleaner().
                            addProtectedFileRange(0L);
                        scanner.scan();
                        processor.close();
                    } catch (Throwable T) {
                        if (T == SHUTDOWN_REQUESTED_EXCEPTION) {
                            /* Shutdown was requested.  Don't rethrow. */
                            return;
                        }

                        processor.setException(T);
                        queue.offer(END_OF_QUEUE);
                    } finally {
                        /* Allow files to be deleted again. */
                        dbImpl.getDbEnvironment().getCleaner().
                            removeProtectedFileRange(0L);
                    }
                }
            };

        this.producer.setName("DiskOrderedCursor Producer Thread for " +
                              Thread.currentThread());
        this.producer.start();
    }

    private class Processor implements DiskOrderedScanner.RecordProcessor {

        /*
         * A place to stash any exception caught by the producer thread so that
         * it can be returned to the application.
         */
        private Throwable exception;

        private volatile boolean shutdownNow;

        public void process(byte[] key, byte[] data) {
            checkShutdown();
            try {
                KeyAndData e = new KeyAndData(key, data);
                while (!queue.offer(e, offerTimeout,
                                    TimeUnit.MILLISECONDS)) {
                    checkShutdown();
                }
            } catch (InterruptedException IE) {
                setException(
                    new ThreadInterruptedException(dbImpl.getEnvironmentImpl(),
                                                   IE));
                setShutdown();
            }
        }

        public boolean canProcessWithoutBlocking(int nRecords) {
            return queue.remainingCapacity() >= nRecords;
        }

        void close() {
            try {
                if (!queue.offer(END_OF_QUEUE, offerTimeout,
                                 TimeUnit.MILLISECONDS)) {
                    /* Cursor.close() called, but queue was not drained. */
                    setException(SHUTDOWN_REQUESTED_EXCEPTION.
                                 fillInStackTrace());
                    setShutdown();
                }
            } catch (InterruptedException IE) {
                setException(
                    new ThreadInterruptedException(dbImpl.getEnvironmentImpl(),
                                                   IE));
                setShutdown();
            }
        }

        void setException(Throwable t) {
            exception = t;
        }

        private Throwable getException() {
            return exception;
        }

        private void setShutdown() {
            shutdownNow = true;
        }

        void checkShutdown() {
            if (shutdownNow) {
                throw SHUTDOWN_REQUESTED_EXCEPTION;
            }
        }
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized void close() {
        if (closed) {
            return;
        }

        /* Tell Producer Thread to die if it hasn't already. */
        processor.setShutdown();

        closed = true;
    }

    public void checkEnv() {
        dbImpl.getDbEnvironment().checkIfInvalid();
    }

    private OperationStatus setData(final DatabaseEntry foundKey,
                                    final DatabaseEntry foundData) {

        LN.setEntry(foundKey, currentNode.getKey());
        LN.setEntry(foundData, currentNode.getData());
        return OperationStatus.SUCCESS;
    }

    public synchronized
        OperationStatus getCurrent(final DatabaseEntry foundKey,
                                   final DatabaseEntry foundData) {

        if (closed) {
            throw new IllegalStateException("ForwardCursor not initialized");
        }

        if (currentNode == END_OF_QUEUE) {
            return OperationStatus.KEYEMPTY;
        }

        return setData(foundKey, foundData);
    }

    public synchronized
        OperationStatus getNext(final DatabaseEntry foundKey,
                                final DatabaseEntry foundData) {

        if (closed) {
            throw new IllegalStateException("ForwardCursor not initialized");
        }

        /*
         * If NOTFOUND was returned earlier, do not enter loop below to avoid a
         * hang.  [#21282]
         */
        if (currentNode == END_OF_QUEUE) {
            return OperationStatus.NOTFOUND;
        }

        try {

            /*
             * Poll in a loop in case the producer thread throws an exception
             * and can't put END_OF_QUEUE on the queue because of an
             * InterruptedException.  The presence of an exception is the last
             * resort to make sure that getNext actually returns to the user.
             */
            do {
                currentNode = queue.poll(1, TimeUnit.SECONDS);
                if (processor.getException() != null) {
                    break;
                }
            } while (currentNode == null);
        } catch (InterruptedException IE) {
            currentNode = END_OF_QUEUE;
            throw new ThreadInterruptedException(dbImpl.getEnvironmentImpl(),
                                                 IE);
        }

        if (processor.getException() != null) {
            throw new DiskOrderedCursorProducerException
                ("Producer Thread Failure", processor.getException());
        }

        if (currentNode == END_OF_QUEUE) {
            return OperationStatus.NOTFOUND;
        }

        return setData(foundKey, foundData);
    }

    /**
     * For testing and other internal use.
     */
    public int remainingQueueCapacity() {
        return queue.remainingCapacity();
    }

    /**
     * For testing and other internal use.
     */
    public int getNScannerIterations() {
        return scanner.getNIterations();
    }
}
