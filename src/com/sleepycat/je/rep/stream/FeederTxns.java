/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.stream;

import static com.sleepycat.je.rep.stream.FeederTxnStatDefinition.ACK_WAIT_MS;
import static com.sleepycat.je.rep.stream.FeederTxnStatDefinition.TOTAL_TXN_MS;
import static com.sleepycat.je.rep.stream.FeederTxnStatDefinition.TXNS_ACKED;
import static com.sleepycat.je.rep.stream.FeederTxnStatDefinition.TXNS_NOT_ACKED;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.rep.InsufficientAcksException;
import com.sleepycat.je.rep.impl.RepImpl;
import com.sleepycat.je.rep.txn.MasterTxn;
import com.sleepycat.je.txn.Txn;
import com.sleepycat.je.utilint.AtomicLongStat;
import com.sleepycat.je.utilint.StatGroup;
import com.sleepycat.je.utilint.VLSN;

/**
 * FeederTxns manages transactions that need acknowledgments.
 */
public class FeederTxns {

    /*
     * Tracks transactions that have not yet been acknowledged for the entire
     * replication node.
     */
    private final Map<Long, TxnInfo> txnMap;

    private final RepImpl repImpl;
    private final StatGroup statistics;
    private final AtomicLongStat txnsAcked;
    private final AtomicLongStat txnsNotAcked;
    private final AtomicLongStat ackWaitMs;
    private final AtomicLongStat totalTxnMs;

    public FeederTxns(RepImpl repImpl) {

        txnMap = new ConcurrentHashMap<Long, TxnInfo>();
        this.repImpl = repImpl;
        statistics = new StatGroup(FeederTxnStatDefinition.GROUP_NAME,
                                   FeederTxnStatDefinition.GROUP_DESC);
        txnsAcked = new AtomicLongStat(statistics, TXNS_ACKED);
        txnsNotAcked = new AtomicLongStat(statistics, TXNS_NOT_ACKED);
        ackWaitMs = new AtomicLongStat(statistics, ACK_WAIT_MS);
        totalTxnMs = new AtomicLongStat(statistics, TOTAL_TXN_MS);
    }

    /**
     * Create a new TxnInfo so that transaction commit can wait on the latch it
     * sets up.
     *
     * @param txn identifies the transaction.
     */
    public void setupForAcks(MasterTxn txn) {
        if (txn.getRequiredAckCount() == 0) {
            /* No acks called for, no setup needed. */
            return;
        }
        TxnInfo txnInfo = new TxnInfo(txn);
        TxnInfo  prevInfo = txnMap.put(txn.getId(), txnInfo);
        assert(prevInfo == null);
    }

    /**
     * Returns the transaction if it's waiting for acknowledgments. Returns
     * null otherwise.
     */
    public MasterTxn getAckTxn(long txnId) {
        TxnInfo txnInfo = txnMap.get(txnId);
        return (txnInfo == null) ? null : txnInfo.txn;
    }

    /*
     * Clears any ack requirements associated with the transaction. It's
     * typically invoked on a transaction abort.
     */
    public void clearTransactionAcks(Txn txn) {
        txnMap.remove(txn.getId());
    }

    /**
     * Notes that an acknowledgment was received from a replica.
     *
     * @param txnId the locally committed transaction that was acknowledged.
     *
     * @return the transaction VLSN, if txnId needs an ack, null otherwise
     */
    public VLSN noteReplicaAck(long txnId) {
        if (!repImpl.getRepNode().getDurabilityQuorum().ackQualifies(txnId)) {
            return null;
        }
        final TxnInfo txnInfo = txnMap.get(txnId);
        if (txnInfo == null) {
            return null;
        }
        txnInfo.countDown();
        return txnInfo.getCommitVLSN();
    }

    /**
     * Waits for the required number of replica acks to come through.
     *
     * @param txn identifies the transaction to wait for.
     *
     * @param timeoutMs the amount of time to wait for the acknowledgments
     * before giving up.
     *
     * @throws InsufficientAcksException if the ack requirements were not met
     */
    public void awaitReplicaAcks(MasterTxn txn, int timeoutMs)
        throws InterruptedException {

        TxnInfo txnInfo = txnMap.get(txn.getId());
        if (txnInfo == null) {
            return;
        }
        txnInfo.await(timeoutMs);
        txnMap.remove(txn.getId());
        repImpl.getRepNode().getDurabilityQuorum().ensureSufficientAcks
            (txnInfo, timeoutMs);
    }

    /**
     * Used to track the latch and the transaction information associated with
     * a transaction needing an acknowledgment.
     */
    public class TxnInfo {
        /* The latch used to track transaction acknowledgments. */
        final private CountDownLatch latch;
        final MasterTxn txn;

        private TxnInfo(MasterTxn txn) {
            assert(txn != null);
            final int numRequiredAcks = txn.getRequiredAckCount();
            this.latch = (numRequiredAcks == 0) ?
                null :
                new CountDownLatch(numRequiredAcks);
            this.txn = txn;
        }

        /**
         * Returns the VLSN associated with the committed txn, or null if the
         * txn has not yet been committed.
         */
        public VLSN getCommitVLSN() {
            return txn.getCommitVLSN();
        }

        private final boolean await(int timeoutMs)
            throws InterruptedException {

            final long ackAwaitStartMs = System.currentTimeMillis();
            boolean isZero = (latch == null) ||
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (isZero) {
                txnsAcked.increment();
                final long now = System.currentTimeMillis();
                ackWaitMs.add(now - ackAwaitStartMs);
                totalTxnMs.add(now - txn.getStartMs());
            } else {
                txnsNotAcked.increment();
            }
            return isZero;
        }

        public final void countDown() {
            if (latch == null) {
                return;
            }

            latch.countDown();
        }

        public final int getPendingAcks() {
            if (latch == null) {
                return 0;
            }

            return (int) latch.getCount();
        }

        public final MasterTxn getTxn() {
            return txn;
        }
    }

    public StatGroup getStats() {
        StatGroup ret = statistics.cloneGroup(false);

        return ret;
    }

    public void resetStats() {
        statistics.clear();
    }

    public StatGroup getStats(StatsConfig config) {

        StatGroup cloneStats = statistics.cloneGroup(config.getClear());

        return cloneStats;
    }
}
