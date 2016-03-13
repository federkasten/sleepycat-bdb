/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.log;

import static com.sleepycat.je.log.LogStatDefinition.LBFP_BUFFER_BYTES;
import static com.sleepycat.je.log.LogStatDefinition.LBFP_LOG_BUFFERS;
import static com.sleepycat.je.log.LogStatDefinition.LBFP_MISS;
import static com.sleepycat.je.log.LogStatDefinition.LBFP_NOT_RESIDENT;
import static com.sleepycat.je.log.LogStatDefinition.LBFP_NO_FREE_BUFFER;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedList;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.dbi.DbConfigManager;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.latch.Latch;
import com.sleepycat.je.utilint.AtomicLongStat;
import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.je.utilint.IntStat;
import com.sleepycat.je.utilint.LongStat;
import com.sleepycat.je.utilint.StatGroup;

/**
 * LogBufferPool manages a circular pool of LogBuffers.
 * The currentWriteBuffer is the buffer that is currently
 * used to add data.  When the buffer is full, the next (adjacent)
 * buffer is made available for writing. The buffer pool has a dirty
 * list of buffers. A buffer becomes a member of the dirty list when the
 * currentWriteBuffer is moved to another buffer. Buffers are removed
 * from the dirty list when they are written.
 * The dirtyStart/dirtyEnd variables indicate the list of dirty buffers.
 * A value of -1 for either variables indicates that there are no dirty
 * buffers. These variable are synchronized via the
 * LogBufferPool.bufferPoolLatch. The LogManager.logWriteLatch (aka LWL)
 * is used to synchronize access to the currentWriteBuffer.
 *
 * A buffer used for writing is accessed by the getWriteBuffer method.
 * This method must be called while holding the LWL. The buffer returned
 * is not latched but the caller gets exclusive access to it because
 * it is the currentWriteBuffer.
 *
 * A LogBuffer has a pin count (LogBuffer.writePinCount) associated with
 * it. The pin count is incremented when space is allocated in the buffer.
 * The allocation of space is serialized under the LWL. Threads will add
 * data to the buffer without latching. After the data is added, the
 * pin count is decremented. A buffer cannot be used for reading unless
 * the pin count is zero. It should be noted that the increment of the
 * pin count is done with the buffer latched. The decrement does not
 * latch the buffer.
 *
 * Read access to a log buffer is allowed only if the buffer is latched
 * and the pin count is zero. A thread that attempt to access a log
 * buffer for reading will latch and check the pin count. If the pin
 * count is not zero, the latch is released and the process is retried.
 * The thread attempting to access the log buffer for reading may be delayed.
 * The worst case is when the reader has to wait until the buffer
 * is filled (the pin count would be zero).
 */
class LogBufferPool {
    private static final String DEBUG_NAME = LogBufferPool.class.getName();

    private EnvironmentImpl envImpl = null;
    private int logBufferSize;      // size of each log buffer
    private LinkedList<LogBuffer> bufferPool;
    // changed under buffer pool latch.
    private int dirtyStart = -1;
    private int dirtyEnd = -1;

    /*
     * Buffer that holds the current log end.  All writes go
     * to this buffer.The members are protected by
     * the LogManager.logWriteLatch.
     */
    private LogBuffer currentWriteBuffer;
    private int currentWriteBufferIndex;

    private final FileManager fileManager;

    /* Stats */
    private final StatGroup stats;
    private final AtomicLongStat nNotResident;  // had to be instantiated from an LSN
    private final AtomicLongStat nCacheMiss;    // had to retrieve from disk
    private final IntStat logBuffers;
    private final LongStat nBufferBytes;

    /*
     * Number of times that current write pointer could not be incremented
     * because there was no non-dirty buffer.
     */
    private final LongStat nNoFreeBuffer;

    private final boolean runInMemory;

    /*
     * bufferPoolLatch synchronizes access and changes to the buffer pool.
     * Related latches are the log write latch in LogManager and the read
     * latches in each log buffer. The log write latch is always taken before
     * the bufferPoolLatch. The bufferPoolLatch is always taken before any
     * logBuffer read latch. When faulting in an object from the log, the order
     * of latching is:
     *          bufferPoolLatch.acquire()
     *          LogBuffer read latch acquire();
     *          bufferPoolLatch.release();
     *          LogBuffer read latch release()
     * bufferPoolLatch is also used to protect assignment to the
     * currentWriteBuffer field.
     */
    private final Latch bufferPoolLatch;

    /*
     * A minimum LSN property for the pool that can be checked without
     * latching, to reduce contention by readers. An LSN less than minBufferLsn
     * is guaranteed not to be in the pool. An LSN greater or equal to
     * minBufferLsn may or may not be in the pool, and latching is necessary to
     * determine this.  Initializing minBufferLsn to zero ensures that we will
     * latch and check the pool until it is initialized with a valid LSN.
     * [#19642]
     */
    private volatile long minBufferLsn = 0;

    LogBufferPool(FileManager fileManager,
                  EnvironmentImpl envImpl)
        throws DatabaseException {

        this.fileManager = fileManager;
        this.envImpl = envImpl;
        bufferPoolLatch = new Latch(DEBUG_NAME + "_FullLatch");

        /* Configure the pool. */
        DbConfigManager configManager = envImpl.getConfigManager();
        runInMemory = envImpl.isMemOnly();
        reset(configManager);

        /* Current buffer is the active buffer that writes go into. */
        currentWriteBuffer = bufferPool.getFirst();
        currentWriteBufferIndex = 0;

        stats = new StatGroup(LogStatDefinition.LBF_GROUP_NAME,
                              LogStatDefinition.LBF_GROUP_DESC);
        nNotResident = new AtomicLongStat(stats, LBFP_NOT_RESIDENT);
        nCacheMiss = new AtomicLongStat(stats, LBFP_MISS);
        logBuffers = new IntStat(stats, LBFP_LOG_BUFFERS);
        nBufferBytes = new LongStat(stats, LBFP_BUFFER_BYTES);
        nNoFreeBuffer = new LongStat(stats, LBFP_NO_FREE_BUFFER);
    }

    final int getLogBufferSize() {
        return logBufferSize;
    }

    /**
     * Initialize the pool at construction time and when the cache is resized.
     * This method is called after the memory budget has been calculated.
     * The log write latch must be held if the cache is being resized.
     */
    void reset(DbConfigManager configManager)
        throws DatabaseException {

        /*
         * When running in memory, we can't clear the existing pool and
         * changing the buffer size is not very useful, so just return.
         */
        if (runInMemory && bufferPool != null) {
            return;
        }

        /*
         * Write the currentWriteBuffer to the file and reset
         * currentWriteBuffer.
         */
        if (currentWriteBuffer != null) {
            bumpAndWriteSynced(0, true);
        }

        /*
         * Based on the log budget, figure the number and size of
         * log buffers to use.
         */
        int numBuffers =
            configManager.getInt(EnvironmentParams.NUM_LOG_BUFFERS);
        long logBufferBudget = envImpl.getMemoryBudget().getLogBufferBudget();

        long logFileSize =
            configManager.getLong(EnvironmentParams.LOG_FILE_MAX);
        /* Buffers must be int sized. */
        int newBufferSize = (int) logBufferBudget / numBuffers;
        /* Limit log buffer size to size of a log file. */
        newBufferSize = Math.min(newBufferSize, (int) logFileSize);
        /* list of buffers that are available for log writing */
        LinkedList<LogBuffer> newPool = new LinkedList<LogBuffer>();

        /*
         * If we're running in memory only, don't pre-allocate all the buffers.
         * This case only occurs when called from the constructor.
         */
        if (runInMemory) {
            numBuffers = 1;
        }

        for (int i = 0; i < numBuffers; i++) {
            newPool.add(new LogBuffer(newBufferSize, envImpl));
        }

        /*
         * The following applies when this method is called to reset the pool
         * when an existing pool is in use:
         * - The old pool will no longer be referenced.
         * - Buffers being read in the old pool will be no longer referenced
         * after the read operation is complete.
         * - The currentWriteBuffer field is not changed here; it will be no
         * longer referenced after it is written to the file and a new
         * currentWriteBuffer is assigned.
         * - The logBufferSize can be changed now because it is only used for
         * allocating new buffers; it is not used as the size of the
         * currentWriteBuffer.
         */
        bufferPoolLatch.acquire();
        bufferPool = newPool;
        logBufferSize = newBufferSize;
        /* Current buffer is the active buffer that writes go into. */
        currentWriteBuffer = bufferPool.getFirst();
        currentWriteBufferIndex = 0;
        bufferPoolLatch.release();
    }

    /**
     * Get a log buffer for writing sizeNeeded bytes. If currentWriteBuffer is
     * too small or too full, flush currentWriteBuffer and get a new one.
     * If the new buffer is still too small, then write all dirty buffers
     * since the current algorithm allocates temporary memory for the buffer
     * and writes to the file outside of the normal log buffers.
     * Called within the log write latch. Since the caller has the LWL,
     * the caller has access to the buffer (currentWriteBuffer) without
     * latching the buffer.
     *
     * @param sizeNeeded used only if running in memory. Size of the log buffer
     * @param flippedFile true bump current buffer, write buffer and finish
     *         log file.
     *
     * @return a buffer that can hold sizeNeeded bytes.
     */
    LogBuffer getWriteBuffer(int sizeNeeded, boolean flippedFile)
        throws IOException, DatabaseException {

        /*
         * We need a new log buffer either because this log buffer is full, or
         * the LSN has marched along to the next file.  Each log buffer only
         * holds entries that belong to a single file.  If we've flipped over
         * into the next file, we'll need to get a new log buffer even if the
         * current one has room.
         */
        if (flippedFile) {

            /*
             * Write the currentWriteBuffer to the file and reset
             * currentWriteBuffer.
             */
            bumpAndWriteSynced(sizeNeeded, true);
            /* Now that the old buffer has been written to disk, fsync. */
            if (!runInMemory) {
                fileManager.syncLogEndAndFinishFile();
            }
        } else if (!currentWriteBuffer.hasRoom(sizeNeeded)) {

            /*
             * Try to bump the current write buffer since there
             * was not enough space in the current write buffer.
             */

            if (!bumpCurrent(sizeNeeded) ||
                !currentWriteBuffer.hasRoom(sizeNeeded) ) {

                /*
                 * We could not bump because there was no free
                 * buffer, or the item is larger than the buffer size.
                 * Write the dirties to free a buffer up, or to flush
                 * in preparation for writing a temporary buffer.
                 */
                bumpAndWriteSynced(sizeNeeded, false);
            }
        }

        return currentWriteBuffer;
    }

    /**
     * Bump current write buffer and write the dirty buffers. Called under
     * the log write latch in order to insure that if there are no free
     * buffers, we can write the dirty and have a free one required to
     * bump the current write buffer to.
     * @param sizeNeeded used only if running in memory. Size of the log buffer
     * @param flushRequired true data is written to log otherwise data
     *        may be placed on the write queue.
     */
    void bumpAndWriteSynced(int sizeNeeded, boolean flushRequired) {

        /*
         * Write the currentWriteBuffer to the file and reset
         * currentWriteBuffer.
         */
        if (!bumpCurrent(sizeNeeded)) {

            /*
             *  Could not bump the current write buffer; no clean buffers.
             *  Write the current dirty buffers so we can bump.
             */
            writeDirty(flushRequired);

            if (bumpCurrent(sizeNeeded)) {

                /*
                 * Since we have the log write latch we should be
                 * able to bump the current buffer.
                 */
                writeDirty(flushRequired);
            } else {
                /* should not ever get here */
                throw EnvironmentFailureException.unexpectedState(
                    envImpl, "No free log buffers.");
            }

        } else {

            /*
             * Since we have the log write latch we should be
             * able to bump the current buffer.
             */
            writeDirty(flushRequired);
        }
    }

    /**
     * Returns the next buffer slot number from the
     * input buffer slot number The slots are are
     * a circular buffer.
     * @param slotNumber
     * @return the next slot number
     */
    private int getNextSlot(int slotNumber) {
       return  (slotNumber < (bufferPool.size() -1)) ? ++slotNumber : 0;
    }

    /**
     * Writes the dirty log buffers.
     * @param flushRequired true then data is written to file otherwise
     *        the data may be placed on the FileManager WriteQueue.
     */
    void writeDirty(boolean flushRequired) {
        bufferPoolLatch.acquire();
        try {
            if (dirtyStart < 0) {
                return;
            }
            boolean process = true;
            do {
                LogBuffer lb = bufferPool.get(dirtyStart);
                lb.waitForZeroAndLatch();
                try {
                    writeBufferToFile(lb, flushRequired);
                } finally {
                    lb.release();
                }
                if (dirtyStart == dirtyEnd) {
                    process = false;
                } else {
                    dirtyStart = getNextSlot(dirtyStart);
                }
            } while (process);
            dirtyStart = -1;
            dirtyEnd = -1;
        } finally {
            bufferPoolLatch.releaseIfOwner();
        }
    }

    /**
     * Writes a log buffer. The log buffer must be latched.
     * @param latchedBuffer buffer to write
     * @param flushRequired true then data is written to file otherwise
     *        the data may be placed on the FileManager WriteQueue.
     */
    private void writeBufferToFile(LogBuffer latchedBuffer,
                                   boolean flushRequired) {

        if (runInMemory) {
            return;
        }

        try {
            ByteBuffer currentByteBuffer = latchedBuffer.getDataBuffer();
            int savePosition = currentByteBuffer.position();
            int saveLimit = currentByteBuffer.limit();
            currentByteBuffer.flip();

            /*
             * If we're configured for writing (not memory-only situation),
             * write this buffer to disk and find a new buffer to use.
             */
            try {
                fileManager.writeLogBuffer(latchedBuffer, flushRequired);
            } catch (Throwable t) {
                currentByteBuffer.position(savePosition);
                currentByteBuffer.limit(saveLimit);

                /*
                 * Exceptions thrown during logging are expected to be
                 * fatal. Ensure that the environment is invalidated
                 * when a non-fatal exception is unexpectedly thrown.
                 *
                 * However, we allow the environment to remain valid
                 * when testing IOExceptions. In IOExceptionTest, we
                 * test a possible future feature where the environment
                 * is not invalidated by disk-full.
                 */
                 if (FileManager.continueAfterWriteException()) {
                     if (t instanceof RuntimeException) {
                         throw (RuntimeException)t;
                     }
                 }

                 if (t instanceof EnvironmentFailureException) {

                    /*
                     * If we've already invalidated the environment,
                     * re-throw so as not to excessively wrap the
                     * exception.
                     */
                    if (!envImpl.isValid()) {
                        throw (EnvironmentFailureException)t;
                    }
                    /* Otherwise, invalidate the environment. */
                    throw EnvironmentFailureException.unexpectedException(
                        envImpl, (EnvironmentFailureException)t);
                } else if (t instanceof Error) {
                    envImpl.invalidate((Error)t);
                    throw (Error)t;
                } else if (t instanceof Exception) {
                    throw EnvironmentFailureException.unexpectedException(
                        envImpl, (Exception)t);
                } else {
                    throw EnvironmentFailureException.unexpectedException(
                        envImpl, t.getMessage(), null);
                }
            }
        } finally {
            if (latchedBuffer != null) {
                latchedBuffer.release();
            }
        }
    }

    /**
     * Move the current write buffer to the next. Called under the
     * log write latch. Will not bump the current write buffer if the
     * buffer is empty.
     *
     * @param sizeNeeded used only if running in memory. Size of the log
     *        buffer that is needed.
     * @return true if write buffer is bumped, or false if write buffer is not
     *         bumped. False is returned when there are no free buffers.
     */
     boolean bumpCurrent(int sizeNeeded) {

        /* We're done with the buffer, flip to make it readable. */
        bufferPoolLatch.acquire();
        currentWriteBuffer.latchForWrite();

        LogBuffer latchedBuffer = currentWriteBuffer;
        try {

            /*
             * Is there anything in this write buffer?
             */
            if (currentWriteBuffer.getFirstLsn() == DbLsn.NULL_LSN) {
                return true;
            }

            if (runInMemory) {
                int bufferSize =
                    ((logBufferSize > sizeNeeded) ?
                        logBufferSize : sizeNeeded);
                /* We're supposed to run in-memory, allocate another buffer. */
                currentWriteBuffer = new LogBuffer(bufferSize, envImpl);
                bufferPool.add(currentWriteBuffer);
                currentWriteBufferIndex = bufferPool.size() - 1;
                return true;
            }

            if (dirtyStart < 0) {
                dirtyStart = currentWriteBufferIndex;
            } else {
                /* Check to see if there is an undirty buffer to use. */
                if (getNextSlot(currentWriteBufferIndex) == dirtyStart) {
                    nNoFreeBuffer.increment();
                    return false;
                }
            }

            dirtyEnd = currentWriteBufferIndex;
            currentWriteBufferIndex = getNextSlot(currentWriteBufferIndex);
            LogBuffer nextToUse = bufferPool.get(currentWriteBufferIndex);
            LogBuffer newInitialBuffer =
                bufferPool.get(getNextSlot(currentWriteBufferIndex));
            nextToUse.reinit();

            /* Assign currentWriteBuffer with the latch held. */
            currentWriteBuffer = nextToUse;

            /* Paranoia: do this after transition to new buffer. */
            updateMinBufferLsn(newInitialBuffer);
            return true;
        } finally {
            latchedBuffer.release();
            bufferPoolLatch.releaseIfOwner();
        }
    }

    /**
     * Set minBufferLsn to start of new initial buffer.  The update occurs only
     * after cycling once through the buffers in the pool.  This is a simple
     * implementation, and waiting until we've filled the buffer pool to
     * initialize it is sufficient for reducing read contention in
     * getReadBufferByLsn.  [#19642]
     */
    private void updateMinBufferLsn(final LogBuffer newInitialBuffer) {
        final long newMinLsn = newInitialBuffer.getFirstLsn();
        if (newMinLsn != DbLsn.NULL_LSN) {
            minBufferLsn = newMinLsn;
        }
    }

    /**
     * Find a buffer that contains the given LSN location.
     *
     * @return the buffer that contains the given LSN location, latched and
     * ready to read, or return null.
     */
    LogBuffer getReadBufferByLsn(long lsn)
        throws DatabaseException {

        nNotResident.increment();

        /* Avoid latching if the LSN is known not to be in the pool. */
        if (DbLsn.compareTo(lsn, minBufferLsn) < 0) {
            nCacheMiss.increment();
            return null;
        }

        /* Latch and check the buffer pool. */
        bufferPoolLatch.acquire();
        try {
            for (LogBuffer l : bufferPool) {
                if (l.containsLsn(lsn)) {
                    return l;
                }
            }

            nCacheMiss.increment();
            return null;
        } finally {
            bufferPoolLatch.releaseIfOwner();
        }
    }

    StatGroup loadStats(StatsConfig config)
        throws DatabaseException {

        /* Also return buffer pool memory usage */
        bufferPoolLatch.acquire();
        long bufferBytes = 0;
        int nLogBuffers = 0;
        try {
            Iterator<LogBuffer> iter = bufferPool.iterator();
            while (iter.hasNext()) {
                LogBuffer l = iter.next();
                nLogBuffers++;
                bufferBytes += l.getCapacity();
            }
        } finally {
            bufferPoolLatch.release();
        }
        logBuffers.set(nLogBuffers);
        nBufferBytes.set(bufferBytes);

        return stats.cloneGroup(config.getClear());
    }

    /**
     * Return the current nCacheMiss statistic in a lightweight fashion,
     * without perturbing other statistics or requiring synchronization.
     */
    public long getNCacheMiss() {
        return nCacheMiss.get();
    }

    /**
     * For unit testing.
     */
    public StatGroup getBufferPoolLatchStats() {
        return bufferPoolLatch.getLatchStats();
    }
}
