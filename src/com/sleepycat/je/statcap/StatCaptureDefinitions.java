/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.statcap;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import com.sleepycat.je.cleaner.CleanerStatDefinition;
import com.sleepycat.je.dbi.DbiStatDefinition;
import com.sleepycat.je.evictor.Evictor.EvictionSource;
import com.sleepycat.je.evictor.EvictorStatDefinition;
import com.sleepycat.je.incomp.INCompStatDefinition;
import com.sleepycat.je.latch.LatchStatDefinition;
import com.sleepycat.je.log.LogStatDefinition;
import com.sleepycat.je.recovery.CheckpointStatDefinition;
import com.sleepycat.je.txn.LockStatDefinition;
import com.sleepycat.je.utilint.StatDefinition;

/**
 * Used to define the statistics that are projected into the
 * statistics file.
 *
 */
public class StatCaptureDefinitions {

    protected Map<String, StatDefinition> nameToDef;

    private static StatDefinition[] cleanerStats = {
        CleanerStatDefinition.CLEANER_BACKLOG,
        CleanerStatDefinition.CLEANER_FILE_DELETION_BACKLOG,
        CleanerStatDefinition.CLEANER_RUNS,
        CleanerStatDefinition.CLEANER_PROBE_RUNS,
        CleanerStatDefinition.CLEANER_DELETIONS,
        CleanerStatDefinition.CLEANER_PENDING_LN_QUEUE_SIZE,
        CleanerStatDefinition.CLEANER_INS_OBSOLETE,
        CleanerStatDefinition.CLEANER_INS_CLEANED,
        CleanerStatDefinition.CLEANER_INS_DEAD,
        CleanerStatDefinition.CLEANER_INS_MIGRATED,
        CleanerStatDefinition.CLEANER_BIN_DELTAS_OBSOLETE,
        CleanerStatDefinition.CLEANER_BIN_DELTAS_CLEANED,
        CleanerStatDefinition.CLEANER_BIN_DELTAS_DEAD,
        CleanerStatDefinition.CLEANER_BIN_DELTAS_MIGRATED,
        CleanerStatDefinition.CLEANER_LNS_OBSOLETE,
        CleanerStatDefinition.CLEANER_LNS_CLEANED,
        CleanerStatDefinition.CLEANER_LNS_DEAD,
        CleanerStatDefinition.CLEANER_LNS_LOCKED,
        CleanerStatDefinition.CLEANER_LNS_MIGRATED,
        CleanerStatDefinition.CLEANER_LNS_MARKED,
        CleanerStatDefinition.CLEANER_LNQUEUE_HITS,
        CleanerStatDefinition.CLEANER_PENDING_LNS_PROCESSED,
        CleanerStatDefinition.CLEANER_MARKED_LNS_PROCESSED,
        CleanerStatDefinition.CLEANER_TO_BE_CLEANED_LNS_PROCESSED,
        CleanerStatDefinition.CLEANER_CLUSTER_LNS_PROCESSED,
        CleanerStatDefinition.CLEANER_PENDING_LNS_LOCKED,
        CleanerStatDefinition.CLEANER_ENTRIES_READ,
        CleanerStatDefinition.CLEANER_REPEAT_ITERATOR_READS,
        CleanerStatDefinition.CLEANER_TOTAL_LOG_SIZE,
        CleanerStatDefinition.CLEANER_LN_SIZE_CORRECTION_FACTOR,
        CleanerStatDefinition.CLEANER_LAST_KNOWN_UTILIZATION,
    };
    private static StatDefinition[] dbiStats = {
        DbiStatDefinition.MB_SHARED_CACHE_TOTAL_BYTES,
        DbiStatDefinition.MB_TOTAL_BYTES,
        DbiStatDefinition.MB_DATA_BYTES,
        DbiStatDefinition.MB_DATA_ADMIN_BYTES,
        DbiStatDefinition.MB_ADMIN_BYTES,
        DbiStatDefinition.MB_LOCK_BYTES,
    };

    private static StatDefinition[] environmentStats = {
        DbiStatDefinition.ENVIMPL_RELATCHES_REQUIRED,
        DbiStatDefinition.ENVIMPL_CREATION_TIME
        };

    private static StatDefinition[] evictorStats = {
        EvictorStatDefinition.EVICTOR_EVICT_PASSES,
        EvictorStatDefinition.EVICTOR_NODES_SELECTED,
        EvictorStatDefinition.EVICTOR_NODES_SCANNED,
        EvictorStatDefinition.EVICTOR_NODES_EVICTED,
        EvictorStatDefinition.EVICTOR_ROOT_NODES_EVICTED,
        EvictorStatDefinition.EVICTOR_BINS_STRIPPED,
        EvictorStatDefinition.EVICTOR_REQUIRED_EVICT_BYTES,
        EvictorStatDefinition.EVICTOR_SHARED_CACHE_ENVS,
        EvictorStatDefinition.LN_FETCH,
        EvictorStatDefinition.UPPER_IN_FETCH,
        EvictorStatDefinition.BIN_FETCH,
        EvictorStatDefinition.LN_FETCH_MISS,
        EvictorStatDefinition.UPPER_IN_FETCH_MISS,
        EvictorStatDefinition.BIN_FETCH_MISS,
        EvictorStatDefinition.CACHED_UPPER_INS,
        EvictorStatDefinition.CACHED_BINS,
        EvictorStatDefinition.THREAD_UNAVAILABLE,
        EvictorStatDefinition.CACHED_IN_SPARSE_TARGET,
        EvictorStatDefinition.CACHED_IN_NO_TARGET,
        EvictorStatDefinition.CACHED_IN_COMPACT_KEY,
        EvictionSource.CACHEMODE.getAvgBatchStatDef(),
        EvictionSource.CACHEMODE.getBINStatDef(),
        EvictionSource.CACHEMODE.getNumBatchesStatDef(),
        EvictionSource.CACHEMODE.getUpperINStatDef(),
        EvictionSource.CRITICAL.getAvgBatchStatDef(),
        EvictionSource.CRITICAL.getBINStatDef(),
        EvictionSource.CRITICAL.getNumBatchesStatDef(),
        EvictionSource.CRITICAL.getUpperINStatDef(),
        EvictionSource.DAEMON.getAvgBatchStatDef(),
        EvictionSource.DAEMON.getBINStatDef(),
        EvictionSource.DAEMON.getNumBatchesStatDef(),
        EvictionSource.DAEMON.getUpperINStatDef(),
        EvictionSource.EVICTORTHREAD.getAvgBatchStatDef(),
        EvictionSource.EVICTORTHREAD.getBINStatDef(),
        EvictionSource.EVICTORTHREAD.getNumBatchesStatDef(),
        EvictionSource.EVICTORTHREAD.getUpperINStatDef(),
        EvictionSource.MANUAL.getAvgBatchStatDef(),
        EvictionSource.MANUAL.getBINStatDef(),
        EvictionSource.MANUAL.getNumBatchesStatDef(),
        EvictionSource.MANUAL.getUpperINStatDef(),
    };

    private static StatDefinition[] inCompStats = {
        INCompStatDefinition.INCOMP_SPLIT_BINS,
        INCompStatDefinition.INCOMP_DBCLOSED_BINS,
        INCompStatDefinition.INCOMP_CURSORS_BINS,
        INCompStatDefinition.INCOMP_NON_EMPTY_BINS,
        INCompStatDefinition.INCOMP_PROCESSED_BINS,
        INCompStatDefinition.INCOMP_QUEUE_SIZE
    };

    private static StatDefinition[] latchStats = {
        LatchStatDefinition.LATCH_NO_WAITERS,
        LatchStatDefinition.LATCH_SELF_OWNED,
        LatchStatDefinition.LATCH_CONTENTION,
        LatchStatDefinition.LATCH_NOWAIT_SUCCESS,
        LatchStatDefinition.LATCH_NOWAIT_UNSUCCESS,
        LatchStatDefinition.LATCH_RELEASES
    };

    private static StatDefinition[] logStats = {
        LogStatDefinition.FILEMGR_RANDOM_READS,
        LogStatDefinition.FILEMGR_RANDOM_WRITES,
        LogStatDefinition.FILEMGR_SEQUENTIAL_READS,
        LogStatDefinition.FILEMGR_SEQUENTIAL_WRITES,
        LogStatDefinition.FILEMGR_RANDOM_READ_BYTES,
        LogStatDefinition.FILEMGR_RANDOM_WRITE_BYTES,
        LogStatDefinition.FILEMGR_SEQUENTIAL_READ_BYTES,
        LogStatDefinition.FILEMGR_SEQUENTIAL_WRITE_BYTES,
        LogStatDefinition.FILEMGR_FILE_OPENS,
        LogStatDefinition.FILEMGR_OPEN_FILES,
        LogStatDefinition.FILEMGR_BYTES_READ_FROM_WRITEQUEUE,
        LogStatDefinition.FILEMGR_BYTES_WRITTEN_FROM_WRITEQUEUE,
        LogStatDefinition.FILEMGR_READS_FROM_WRITEQUEUE,
        LogStatDefinition.FILEMGR_WRITES_FROM_WRITEQUEUE,
        LogStatDefinition.FILEMGR_WRITEQUEUE_OVERFLOW,
        LogStatDefinition.FILEMGR_WRITEQUEUE_OVERFLOW_FAILURES,
        LogStatDefinition.FSYNCMGR_FSYNCS,
        LogStatDefinition.FSYNCMGR_FSYNC_REQUESTS,
        LogStatDefinition.FSYNCMGR_TIMEOUTS,
        LogStatDefinition.FILEMGR_LOG_FSYNCS,
        LogStatDefinition.GRPCMGR_FSYNC_TIME,
        LogStatDefinition.GRPCMGR_N_GROUP_COMMIT_REQUESTS,
        LogStatDefinition.GRPCMGR_N_GROUP_COMMIT_WAITS,
        LogStatDefinition.GRPCMGR_N_LOG_INTERVAL_EXCEEDED,
        LogStatDefinition.GRPCMGR_N_LOG_MAX_GROUP_COMMIT,
        LogStatDefinition.LOGMGR_REPEAT_FAULT_READS,
        LogStatDefinition.LOGMGR_TEMP_BUFFER_WRITES,
        LogStatDefinition.LOGMGR_END_OF_LOG,
        LogStatDefinition.LBFP_NO_FREE_BUFFER,
        LogStatDefinition.LBFP_NOT_RESIDENT,
        LogStatDefinition.LBFP_MISS,
        LogStatDefinition.LBFP_LOG_BUFFERS,
        LogStatDefinition.LBFP_BUFFER_BYTES
    };

    private static StatDefinition[] checkpointStats = {
        CheckpointStatDefinition.CKPT_CHECKPOINTS,
        CheckpointStatDefinition.CKPT_LAST_CKPTID,
        CheckpointStatDefinition.CKPT_FULL_IN_FLUSH,
        CheckpointStatDefinition.CKPT_FULL_BIN_FLUSH,
        CheckpointStatDefinition.CKPT_DELTA_IN_FLUSH,
        CheckpointStatDefinition.CKPT_LAST_CKPT_START,
        CheckpointStatDefinition.CKPT_LAST_CKPT_END
    };

    private static StatDefinition[] throughputStats = {
        DbiStatDefinition.THROUGHPUT_DB_DELETE,
        DbiStatDefinition.THROUGHPUT_DB_GET,
        DbiStatDefinition.THROUGHPUT_DB_GETSEARCHBOTH,
        DbiStatDefinition.THROUGHPUT_DB_PUT,
        DbiStatDefinition.THROUGHPUT_DB_PUTNODUPDATA,
        DbiStatDefinition.THROUGHPUT_DB_PUTNOOVERWRITE,
        DbiStatDefinition.THROUGHPUT_DB_REMOVESEQUENCE,
        DbiStatDefinition.THROUGHPUT_CURSOR_DELETE,
        DbiStatDefinition.THROUGHPUT_CURSOR_GETCURRENT,
        DbiStatDefinition.THROUGHPUT_CURSOR_GETFIRST,
        DbiStatDefinition.THROUGHPUT_CURSOR_GETLAST,
        DbiStatDefinition.THROUGHPUT_CURSOR_GETNEXT,
        DbiStatDefinition.THROUGHPUT_CURSOR_GETNEXTDUP,
        DbiStatDefinition.THROUGHPUT_CURSOR_GETNEXTNODUP,
        DbiStatDefinition.THROUGHPUT_CURSOR_GETPREV,
        DbiStatDefinition.THROUGHPUT_CURSOR_GETPREVDUP,
        DbiStatDefinition.THROUGHPUT_CURSOR_GETPREVNODUP,
        DbiStatDefinition.THROUGHPUT_CURSOR_PUT,
        DbiStatDefinition.THROUGHPUT_CURSOR_PUTCURRENT,
        DbiStatDefinition.THROUGHPUT_CURSOR_PUTNODUPDATA,
        DbiStatDefinition.THROUGHPUT_CURSOR_PUTNOOVERWRITE,
        DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_DELETE,
        DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETCURRENT,
        DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETFIRST,
        DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETLAST,
        DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETNEXT,
        DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETNEXTDUP,
        DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETNEXTNODUP,
        DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETPREV,
        DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETPREVDUP,
        DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETPREVNODUP,
        DbiStatDefinition.THROUGHPUT_SECONDARYDB_DELETE,
        DbiStatDefinition.THROUGHPUT_SECONDARYDB_GET,
        DbiStatDefinition.THROUGHPUT_SECONDARYDB_GETSEARCHBOTH,
        DbiStatDefinition.THROUGHPUT_DOSCURSOR_GETNEXT
    };

    private static StatDefinition[] lockStats = {
        LockStatDefinition.LOCK_REQUESTS,
        LockStatDefinition.LOCK_WAITS,
    };

    public StatCaptureDefinitions() {
        nameToDef = new HashMap<String, StatDefinition>();
        String groupname = EvictorStatDefinition.GROUP_NAME;
        for (StatDefinition stat : evictorStats) {
            nameToDef.put(groupname + ":" + stat.getName(), stat);
        }
        for (StatDefinition stat : dbiStats) {
            nameToDef.put(groupname + ":" + stat.getName(), stat);
        }
        groupname = CheckpointStatDefinition.GROUP_NAME;
        for (StatDefinition stat : checkpointStats) {
            nameToDef.put(groupname + ":" + stat.getName(), stat);
        }
        groupname = CleanerStatDefinition.GROUP_NAME;
        for (StatDefinition stat : cleanerStats) {
            nameToDef.put(groupname + ":" + stat.getName(), stat);
        }
        groupname = LogStatDefinition.GROUP_NAME;
        for (StatDefinition stat : logStats) {
            nameToDef.put(groupname + ":" + stat.getName(), stat);
        }
        groupname = LockStatDefinition.GROUP_NAME;
        for (StatDefinition stat : lockStats) {
            nameToDef.put(groupname + ":" + stat.getName(), stat);
        }
        for (StatDefinition stat : latchStats) {
            nameToDef.put(groupname + ":" + stat.getName(), stat);
        }
        groupname = DbiStatDefinition.ENV_GROUP_NAME;
        for (StatDefinition stat : environmentStats) {
            nameToDef.put(groupname + ":" + stat.getName(), stat);
        }
        groupname = INCompStatDefinition.GROUP_NAME;
        for (StatDefinition stat : inCompStats) {
            nameToDef.put(groupname + ":" + stat.getName(), stat);
        }
        groupname = DbiStatDefinition.THROUGHPUT_GROUP_NAME;
        for (StatDefinition stat : throughputStats) {
            nameToDef.put(groupname + ":" + stat.getName(), stat);
        }
    }

    public SortedSet<String> getStatisticProjections() {
        SortedSet<String> retval = new TreeSet<String>();
        getProjectionsInternal(retval);
        return retval;
    }

    protected void getProjectionsInternal(SortedSet<String> pmap) {
        String groupname = EvictorStatDefinition.GROUP_NAME;
        for (StatDefinition stat : evictorStats) {
            pmap.add(groupname + ":" + stat.getName());
        }
        for (StatDefinition stat : dbiStats) {
            pmap.add(groupname + ":" + stat.getName());
        }
        groupname = CheckpointStatDefinition.GROUP_NAME;
        for (StatDefinition stat : checkpointStats) {
            pmap.add(groupname + ":" + stat.getName());
        }
        groupname = CleanerStatDefinition.GROUP_NAME;
        for (StatDefinition stat : cleanerStats) {
            pmap.add(groupname + ":" + stat.getName());
        }
        groupname = LogStatDefinition.GROUP_NAME;
        for (StatDefinition stat : logStats) {
            pmap.add(groupname + ":" + stat.getName());
        }
        groupname = LockStatDefinition.GROUP_NAME;
        for (StatDefinition stat : lockStats) {
            pmap.add(groupname + ":" + stat.getName());
        }
        for (StatDefinition stat : latchStats) {
            pmap.add(groupname + ":" + stat.getName());
        }
        groupname = DbiStatDefinition.ENV_GROUP_NAME;
        for (StatDefinition stat : environmentStats) {
            pmap.add(groupname + ":" + stat.getName());
        }
        groupname = INCompStatDefinition.GROUP_NAME;
        for (StatDefinition stat : inCompStats) {
            pmap.add(groupname + ":" + stat.getName());
        }
        groupname = DbiStatDefinition.THROUGHPUT_GROUP_NAME;
        for (StatDefinition stat : throughputStats) {
            pmap.add(groupname + ":" + stat.getName());
        }
    }

    /**
     * Used to get a statistics definition. This method is used
     * for testing purposes only.
     * @param colname in format groupname:statname.
     * @return statistics definition or null of not defined.
     */
    public StatDefinition getDefinition(String colname) {
        return nameToDef.get(colname);
    }
}
