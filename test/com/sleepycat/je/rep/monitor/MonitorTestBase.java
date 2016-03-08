/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.monitor;

import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;

import com.sleepycat.je.rep.impl.RepTestBase;

public class MonitorTestBase extends RepTestBase {

    /* The monitor being tested. */
    protected Monitor monitor;

    @Before
    public void setUp()
        throws Exception {

        super.setUp();
        monitor = createMonitor(100, "mon10000");
    }

    @After
    public void tearDown()
        throws Exception {

        super.tearDown();
        monitor.shutdown();
    }

    protected class TestChangeListener implements MonitorChangeListener {
        String masterNodeName;

        NewMasterEvent masterEvent;
        GroupChangeEvent groupEvent;
        JoinGroupEvent joinEvent;
        LeaveGroupEvent leaveEvent;

        /* Statistics records how may events happen. */
        int masterEvents = 0;
        int groupAddEvents = 0;
        int groupRemoveEvents = 0;
        int joinGroupEvents = 0;
        int leaveGroupEvents = 0;

        /* Barrier to test whether event happens. */
        CountDownLatch masterBarrier;
        CountDownLatch groupBarrier;
        CountDownLatch joinGroupBarrier;
        CountDownLatch leaveGroupBarrier;

        public TestChangeListener() {}

        public void notify(NewMasterEvent newMasterEvent) {
            masterEvents++;
            masterNodeName = newMasterEvent.getNodeName();
            masterEvent = newMasterEvent;
            countDownBarrier(masterBarrier);
        }

        public void notify(GroupChangeEvent groupChangeEvent) {
            switch (groupChangeEvent.getChangeType()) {
                case ADD:
                    groupAddEvents++;
                    break;
                case REMOVE:
                    groupRemoveEvents++;
                    break;
                default:
                    throw new IllegalStateException("Unexpected change type.");
            }
            groupEvent = groupChangeEvent;
            countDownBarrier(groupBarrier);
        }

        public void notify(JoinGroupEvent joinGroupEvent) {
            joinGroupEvents++;
            joinEvent = joinGroupEvent;
            countDownBarrier(joinGroupBarrier);
        }

        public void notify(LeaveGroupEvent leaveGroupEvent) {
            leaveGroupEvents++;
            leaveEvent = leaveGroupEvent;
            countDownBarrier(leaveGroupBarrier);
        }

        void awaitEvent(CountDownLatch latch)
            throws InterruptedException {

            boolean success = latch.await(2 * 60, TimeUnit.SECONDS);
            assertTrue(success);
        }

        private void countDownBarrier(CountDownLatch barrier) {
            if (barrier != null && barrier.getCount() > 0) {
                barrier.countDown();
            }
        }
    }
}
