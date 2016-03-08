/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;

import org.junit.Test;

import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.impl.node.RepNode;
import com.sleepycat.je.rep.monitor.LeaveGroupEvent.LeaveReason;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;

/**
 * Test the MonitorChangeListener behaviors.
 */
public class MonitorChangeListenerTest extends MonitorTestBase {
    private TestChangeListener testListener;

    /**
     * Test basic behaviors of MonitorChangeListener.
     */
    @Test
    public void testBasicBehaviors()
        throws Exception {

        checkGroupStart();

        /*
         * Close the master, expect to see a NewMasterEvent and a
         * LeaveGroupEvent.
         */
        testListener.masterBarrier = new CountDownLatch(1);
        testListener.leaveGroupBarrier = new CountDownLatch(1);
        repEnvInfo[0].closeEnv();
        /* Wait for a LeaveGroupEvent. */
        testListener.awaitEvent(testListener.leaveGroupBarrier);
        /* Wait for elections to settle down. */
        testListener.awaitEvent(testListener.masterBarrier);

        /* Do the check. */
        assertEquals(1, testListener.masterEvents);
        assertEquals(1, testListener.leaveGroupEvents);
        assertTrue(!repEnvInfo[0].getRepConfig().getNodeName().equals
                   (testListener.masterNodeName));

        /*
         * Shutdown all the replicas, see if it generates the expected number
         * of LeaveGroupEvents.
         */
        testListener.leaveGroupEvents = 0;
        shutdownReplicasNormally();
    }

    /* Check the monitor events during the group start up. */
    private void checkGroupStart()
        throws Exception {

        repEnvInfo[0].openEnv();
        RepNode master = repEnvInfo[0].getRepNode();
        assertTrue(master.isMaster());

        testListener = new TestChangeListener();
        testListener.masterBarrier = new CountDownLatch(1);
        testListener.groupBarrier = new CountDownLatch(1);

        /*
         * Start the listener first, so the Listener is guaranteed to get
         * the group change event when the monitor is registered.
         */

        /* generates sync master change event */
        monitor.startListener(testListener);
        /* generates async group change event */
        monitor.register();
        RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);

        /* Make sure it fires a NewMasterEvent, and do the check. */
        testListener.awaitEvent(testListener.masterBarrier);
        assertEquals(1, testListener.masterEvents);
        NewMasterEvent masterEvent = testListener.masterEvent;
        assertEquals(masterEvent.getNodeName(), master.getNodeName());
        assertEquals(masterEvent.getMasterName(), master.getNodeName());

        /* Adding a monitor fires an ADD GroupChangeEvent, do check. */
        testListener.awaitEvent(testListener.groupBarrier);
        assertEquals(1, testListener.groupAddEvents);
        GroupChangeEvent groupEvent = testListener.groupEvent;
        assertEquals(monitor.getNodeName(), groupEvent.getNodeName());

        /* Get the JoinGroupEvents for current active node: master. */
        assertEquals(1, testListener.joinGroupEvents);
        JoinGroupEvent joinEvent = testListener.joinEvent;
        assertEquals(master.getNodeName(), joinEvent.getNodeName());
        assertEquals(master.getNodeName(), joinEvent.getMasterName());

        testListener.masterEvents = 0;
        testListener.joinGroupEvents = 0;
        testListener.groupAddEvents = 0;
        for (int i = 1; i < repEnvInfo.length; i++) {
            testListener.groupBarrier = new CountDownLatch(1);
            testListener.joinGroupBarrier = new CountDownLatch(1);
            repEnvInfo[i].openEnv();
            testListener.awaitEvent(testListener.groupBarrier);
            /* Wait for a JoinGroupEvent. */
            testListener.awaitEvent(testListener.joinGroupBarrier);
            /* No change in master. */
            assertEquals(0, testListener.masterEvents);
            assertEquals(i, testListener.groupAddEvents);
            assertEquals(i, testListener.joinGroupEvents);

            /* Do the GroupChangeEvent check. */
            groupEvent = testListener.groupEvent;
            assertEquals(repEnvInfo[i].getEnv().getNodeName(),
                         groupEvent.getNodeName());
            assertEquals(groupEvent.getRepGroup().getNodes().size(), i + 2);

            /* Do the JoinGroupEvent check. */
            joinEvent = testListener.joinEvent;
            assertEquals(repEnvInfo[i].getEnv().getNodeName(),
                         joinEvent.getNodeName());
            assertEquals(master.getNodeName(), joinEvent.getMasterName());
        }
    }

    /*
     * Shutdown all the replicas normally, do not shutdown the master before
     * shutting down all replicas so that there is no NewMasterEvent
     * generated during this process.
     */
    private void shutdownReplicasNormally()
        throws Exception {

        RepEnvInfo master = null;
        int shutdownReplicas = 0;
        for (RepEnvInfo repInfo : repEnvInfo) {
            ReplicatedEnvironment env = repInfo.getEnv();
            if ((env == null) || !env.isValid()) {
                continue;
            }
            if (env.getState().isMaster()) {
                master = repInfo;
                continue;
            }
            shutdownReplicas++;
            shutdownReplicaAndDoCheck(repInfo, shutdownReplicas);
        }

        /* Shutdown the master. */
        if (master != null) {
            shutdownReplicas++;
            shutdownReplicaAndDoCheck(master, shutdownReplicas);
        }
    }

    /* Shutdown a replica and do the check. */
    private void shutdownReplicaAndDoCheck(RepEnvInfo replica,
                                           int index)
        throws Exception {

        testListener.leaveGroupBarrier = new CountDownLatch(1);
        String nodeName = replica.getEnv().getNodeName();
        replica.closeEnv();
        testListener.awaitEvent(testListener.leaveGroupBarrier);

        /* Do the check. */
        LeaveGroupEvent event = testListener.leaveEvent;
        assertEquals(index, testListener.leaveGroupEvents);
        assertEquals(nodeName, event.getNodeName());
        assertEquals(LeaveReason.NORMAL_SHUTDOWN, event.getLeaveReason());
    }

    /**
     * Test removeMember which would create GroupChangeEvent, but no
     * LeaveGroupEvents.
     */
    @Test
    public void testRemoveMember()
        throws Exception {

        checkGroupStart();

        RepNode master = repEnvInfo[0].getRepNode();
        assertTrue(master.isMaster());

        /*
         * Remove replica from RepGroupDB, see if it fires a REMOVE
         * GroupChangeEvent.
         */
        testListener.groupAddEvents = 0;
        for (int i = 1; i < repEnvInfo.length; i++) {
            testListener.groupBarrier = new CountDownLatch(1);
            master.removeMember(repEnvInfo[i].getRepNode().getNodeName());
            testListener.awaitEvent(testListener.groupBarrier);
            assertEquals(0, testListener.groupAddEvents);
            assertEquals(i, testListener.groupRemoveEvents);
            assertEquals(repEnvInfo[i].getEnv().getNodeName(),
                         testListener.groupEvent.getNodeName());
        }
        assertEquals(0, testListener.leaveGroupEvents);

        /*
         * Shutdown all the replicas, see if it generates the expected number
         * of LeaveGroupEvents.
         */
        shutdownReplicasNormally();
    }

    @Test
    public void testActiveNodesWhenMonitorStarts()
        throws Exception {

        RepTestUtils.joinGroup(repEnvInfo);
        testListener = new TestChangeListener();
        /* generates sync master change event */
        monitor.startListener(testListener);
        /* generates async group change event */
        monitor.register();
        RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);

        assertEquals(1, testListener.masterEvents);
        assertEquals(5, testListener.joinGroupEvents);
        JoinGroupEvent event = testListener.joinEvent;
        assertEquals
            (repEnvInfo[0].getEnv().getNodeName(), event.getMasterName());

        shutdownReplicasNormally();
    }
}
