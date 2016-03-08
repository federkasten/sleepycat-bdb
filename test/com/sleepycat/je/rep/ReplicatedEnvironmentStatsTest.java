/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2011 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep;

import org.junit.Test;

import com.sleepycat.je.rep.impl.RepTestBase;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;

public class ReplicatedEnvironmentStatsTest extends RepTestBase {

    // TODO: more detailed tests check for expected stat return values under
    // simulated conditions.

    /**
     * Exercise every public entry point on master and replica stats.
     */
    @Test
    public void testBasic() {
        createGroup();

        for (RepEnvInfo ri : repEnvInfo) {
            ReplicatedEnvironment rep = ri.getEnv();
            final ReplicatedEnvironmentStats repStats = rep.getRepStats(null);
            invokeAllAccessors(repStats);
        }
    }

    /**
     * Simply exercise the code path
     */
    private void invokeAllAccessors(ReplicatedEnvironmentStats stats) {
        stats.getAckWaitMs();
        stats.getNFeedersCreated();
        stats.getNFeedersShutdown();
        stats.getNMaxReplicaLag();
        stats.getNMaxReplicaLagName();
        stats.getNProtocolBytesRead();
        stats.getNProtocolBytesWritten();
        stats.getNProtocolMessagesRead();
        stats.getNProtocolMessagesWritten();
        stats.getNReplayAborts();
        stats.getNReplayCommitAcks();
        stats.getNReplayCommitNoSyncs();
        stats.getNReplayCommits();
        stats.getNReplayCommitSyncs();
        stats.getNReplayCommitWriteNoSyncs();
        stats.getNReplayLNs();
        stats.getNReplayNameLNs();
        stats.getNReplayGroupCommitMaxExceeded();
        stats.getNReplayGroupCommits();
        stats.getNReplayGroupCommitTimeouts();
        stats.getNReplayGroupCommits();
        stats.getNTxnsAcked();
        stats.getNTxnsNotAcked();
        stats.getProtocolBytesReadRate();
        stats.getProtocolBytesWriteRate();
        stats.getProtocolMessageReadRate();
        stats.getProtocolMessageWriteRate();
        stats.getProtocolReadNanos();
        stats.getProtocolWriteNanos();
        stats.getReplayElapsedTxnTime();
        stats.getReplayMaxCommitProcessingNanos();
        stats.getReplayMinCommitProcessingNanos();
        stats.getReplayTotalCommitProcessingNanos();
        stats.getReplayTotalCommitLagMs();
        stats.getStatGroups();
        stats.getTips();
        stats.getTotalTxnMs();
        stats.getTrackerLagConsistencyWaitMs();
        stats.getTrackerLagConsistencyWaits();
        stats.getTrackerVLSNConsistencyWaitMs();
        stats.getTrackerVLSNConsistencyWaits();
    }
}
