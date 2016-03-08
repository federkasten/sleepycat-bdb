/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.latch;

import static com.sleepycat.je.latch.LatchStatDefinition.LATCH_CONTENTION;
import static com.sleepycat.je.latch.LatchStatDefinition.LATCH_NOWAIT_SUCCESS;
import static com.sleepycat.je.latch.LatchStatDefinition.LATCH_NOWAIT_UNSUCCESS;
import static com.sleepycat.je.latch.LatchStatDefinition.LATCH_NO_WAITERS;
import static com.sleepycat.je.latch.LatchStatDefinition.LATCH_RELEASES;
import static com.sleepycat.je.latch.LatchStatDefinition.LATCH_SELF_OWNED;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.dbi.EnvironmentFailureReason;
import com.sleepycat.je.junit.JUnitThread;
import com.sleepycat.je.utilint.StatGroup;
import com.sleepycat.util.test.TestBase;

public class LatchTest extends TestBase {
    private Latch latch1 = null;
    private Latch latch2 = null;
    private JUnitThread tester1 = null;
    private JUnitThread tester2 = null;

    static private final boolean DEBUG = false;

    private void debugMsg(String message) {
        if (DEBUG) {
            System.out.println(Thread.currentThread().toString()
                               + " " +  message);
        }
    }

    private void initExclusiveLatches() {
        latch1 = new Latch("LatchTest-latch1");
        latch2 = new Latch("LatchTest-latch2");
    }

    @After
    public void tearDown() {
        latch1 = null;
        latch2 = null;
    }

    @Test
    public void testDebugOutput() {
        /* Stupid test solely for the sake of code coverage. */
        initExclusiveLatches();
        /* Acquire a latch. */
        try {
            latch1.acquire();
        } catch (DatabaseException LE) {
            fail("caught DatabaseException");
        }

        LatchSupport.latchesHeldToString();
    }

    @Test
    public void testAcquireAndReacquire()
        throws Throwable {

        initExclusiveLatches();
        JUnitThread tester =
            new JUnitThread("testAcquireAndReacquire") {
                @Override
                public void testBody() {
                    /* Acquire a latch. */
                    try {
                        latch1.acquire();
                    } catch (DatabaseException LE) {
                        fail("caught DatabaseException");
                    }

                    /* Try to acquire it again -- should fail. */
                    try {
                        latch1.acquire();
                        fail("didn't catch UNEXPECTED_STATE");
                    } catch (EnvironmentFailureException expected) {
                        assertSame(EnvironmentFailureReason.
                                   UNEXPECTED_STATE, expected.getReason());
                        assertTrue
                            (latch1.getLatchStats().getInt
                             (LATCH_SELF_OWNED) == 1);
                    } catch (DatabaseException DE) {
                        fail("didn't catch UNEXPECTED_STATE-" +
                             "caught DE instead");
                    }

                    /* Release it. */
                    try {
                        latch1.release();
                    } catch (EnvironmentFailureException e) {
                        fail("unexpected " + e);
                    }

                    /* Release it again -- should fail. */
                    try {
                        latch1.release();
                        fail("didn't catch UNEXPECTED_STATE");
                    } catch (EnvironmentFailureException expected) {
                        assertSame(EnvironmentFailureReason.
                                   UNEXPECTED_STATE, expected.getReason());
                    }
                }
            };

        tester.doTest();
    }

    @Test
    public void testAcquireAndReacquireShared()
        throws Throwable {

        final SharedLatch latch = new SharedLatch("LatchTest-latch2");

        JUnitThread tester =
            new JUnitThread("testAcquireAndReacquireShared") {
                @Override
                public void testBody() {
                    /* Acquire a shared latch. */
                    try {
                        latch.acquireShared();
                    } catch (DatabaseException LE) {
                        fail("caught DatabaseException");
                    }

                    assert latch.isOwner();

                    /* Try to acquire it again -- should succeed. */
                    try {
                        latch.acquireShared();
                    } catch (DatabaseException DE) {
                        fail("caught DE");
                    }

                    assert latch.isOwner();

                    /* Release it. */
                    try {
                        latch.release();
                    } catch (EnvironmentFailureException e) {
                        fail("unexpected " + e);
                    }

                    /* Release it again -- should succeed. */
                    try {
                        latch.release();
                    } catch (EnvironmentFailureException e) {
                        fail("unexpected " + e);
                    }

                    /* Release it again -- should fail. */
                    try {
                        latch.release();
                        fail("didn't catch UNEXPECTED_STATE");
                    } catch (EnvironmentFailureException e) {
                        assertSame(EnvironmentFailureReason.
                                   UNEXPECTED_STATE, e.getReason());
                    }
                }
            };

        tester.doTest();
    }

    /*
     * Do a million acquire/release pairs.  The junit output will tell us how
     * long it took.
     */
    @Test
    public void testAcquireReleasePerformance()
        throws Throwable {

        initExclusiveLatches();
        JUnitThread tester =
            new JUnitThread("testAcquireReleasePerformance") {
                @Override
                public void testBody() {
                    final int N_PERF_TESTS = 1000000;
                    for (int i = 0; i < N_PERF_TESTS; i++) {
                        /* Acquire a latch */
                        try {
                            latch1.acquire();
                        } catch (DatabaseException LE) {
                            fail("caught DatabaseException");
                        }

                        /* Release it. */
                        try {
                            latch1.release();
                        } catch (EnvironmentFailureException e) {
                            fail("unexpected " + e);
                        }
                    }
                    StatGroup stats = latch1.getLatchStats();
                    stats.toString();
                    assertTrue(stats.getInt(LATCH_NO_WAITERS) == N_PERF_TESTS);
                    assertTrue(stats.getInt(LATCH_RELEASES) == N_PERF_TESTS);
                }
            };

        tester.doTest();
    }

    /* Test latch waiting. */

    @Test
    public void testWait()
        throws Throwable {

        initExclusiveLatches();
        for (int i = 0; i < 10; i++) {
            doTestWait();
        }
    }

    private int nAcquiresWithContention = 0;

    public void doTestWait()
        throws Throwable {

        tester1 =
            new JUnitThread("testWait-Thread1") {
                @Override
                public void testBody() {
                    /* Acquire a latch. */
                    try {
                        latch1.acquire();
                    } catch (DatabaseException LE) {
                        fail("caught DatabaseException");
                    }

                    /* Wait for tester2 to try to acquire the latch. */
                    while (latch1.nWaiters() == 0) {
                        Thread.yield();
                    }

                    try {
                        latch1.release();
                    } catch (EnvironmentFailureException e) {
                        fail("unexpected " + e);
                    }
                }
            };

        tester2 =
            new JUnitThread("testWait-Thread2") {
                @Override
                public void testBody() {
                    /* Wait for tester1 to start. */

                    while (latch1.owner() != tester1) {
                        Thread.yield();
                    }

                    /* Acquire a latch. */
                    try {
                        latch1.acquire();
                    } catch (DatabaseException LE) {
                        fail("caught DatabaseException");
                    }

                    assertTrue(latch1.getLatchStats().getInt(LATCH_CONTENTION) 
                               == ++nAcquiresWithContention);

                    /* Release it. */
                    try {
                        latch1.release();
                    } catch (EnvironmentFailureException e) {
                        fail("unexpected " + e);
                    }
                }
            };

        tester1.start();
        tester2.start();
        tester1.finishTest();
        tester2.finishTest();
    }

    /* Test acquireNoWait(). */

    private volatile boolean attemptedAcquireNoWait;

    @Test
    public void testAcquireNoWait()
        throws Throwable {

        initExclusiveLatches();
        tester1 =
            new JUnitThread("testWait-Thread1") {
                @Override
                public void testBody() {
                    debugMsg("Acquiring Latch");
                    /* Acquire a latch. */
                    try {
                        latch1.acquire();
                    } catch (DatabaseException LE) {
                        fail("caught DatabaseException");
                    }

                    /* Wait for tester2 to try to acquire the latch. */

                    debugMsg("Waiting for other thread");
                    while (!attemptedAcquireNoWait) {
                        Thread.yield();
                    }

                    debugMsg("Releasing the latch");
                    try {
                        latch1.release();
                    } catch (EnvironmentFailureException e) {
                        fail("unexpected " + e);
                    }
                }
            };

        tester2 =
            new JUnitThread("testWait-Thread2") {
                @Override
                public void testBody() {
                    /* Wait for tester1 to start. */

                    debugMsg("Waiting for T1 to acquire latch");
                    while (latch1.owner() != tester1) {
                        Thread.yield();
                    }

                    /*
                     * Attempt Acquire with no wait -- should fail since
                     * tester1 has it.
                     */
                    debugMsg("Acquiring no wait");
                    try {
                        assertFalse(latch1.acquireNoWait());
                        assertTrue(latch1.getLatchStats().getInt
                                   (LATCH_NOWAIT_UNSUCCESS) == 1);
                    } catch (DatabaseException LE) {
                        fail("caught DatabaseException");
                    }

                    attemptedAcquireNoWait = true;

                    debugMsg("Waiting for T1 to release latch");
                    while (latch1.owner() != null) {
                        Thread.yield();
                    }

                    /*
                     * Attempt Acquire with no wait -- should succeed now that
                     * tester1 is done.
                     */
                    debugMsg("Acquiring no wait - 2");
                    try {
                        assertTrue(latch1.acquireNoWait());
                        assertTrue(latch1.getLatchStats().getInt
                                   (LATCH_NOWAIT_SUCCESS) == 1);
                    } catch (DatabaseException LE) {
                        fail("caught DatabaseException");
                    }

                    /*
                     * Attempt Acquire with no wait again -- should throw
                     * exception since we already have it.
                     */
                    debugMsg("Acquiring no wait - 3");
                    try {
                        latch1.acquireNoWait();
                        fail("didn't throw UNEXPECTED_STATE");
                    } catch (EnvironmentFailureException expected) {
                        assertSame(EnvironmentFailureReason.
                                   UNEXPECTED_STATE, expected.getReason());
                    } catch (Exception e) {
                        fail("caught Exception");
                    }

                    /* Release it. */
                    debugMsg("releasing the latch");
                    try {
                        latch1.release();
                    } catch (EnvironmentFailureException e) {
                        fail("unexpected " + e);
                    }
                }
            };

        tester1.start();
        tester2.start();
        tester1.finishTest();
        tester2.finishTest();
    }

    /* State for testMultipleWaiters. */
    private final int N_WAITERS = 5;

    /* A JUnitThread that holds the waiter number. */
    private class MultiWaiterTestThread extends JUnitThread {
        private final int waiterNumber;
        public MultiWaiterTestThread(String name, int waiterNumber) {
            super(name);
            this.waiterNumber = waiterNumber;
        }
    }

    @Test
    public void testMultipleWaiters()
        throws Throwable {

        initExclusiveLatches();
        JUnitThread[] waiterThreads =
            new JUnitThread[N_WAITERS];

        tester1 =
            new JUnitThread("testWait-Thread1") {
                @Override
                public void testBody() {

                    debugMsg("About to acquire latch");

                    /* Acquire a latch. */
                    try {
                        latch1.acquire();
                    } catch (DatabaseException LE) {
                        fail("caught DatabaseException");
                    }

                    debugMsg("acquired latch");

                    /*
                     * Wait for all other testers to be waiting on the latch.
                     */
                    while (latch1.nWaiters() < N_WAITERS) {
                        Thread.yield();
                    }

                    debugMsg("About to release latch");

                    try {
                        latch1.release();
                    } catch (EnvironmentFailureException e) {
                        fail("unexpected " + e);
                    }
                }
            };

        for (int i = 0; i < N_WAITERS; i++) {
            waiterThreads[i] =
                new MultiWaiterTestThread("testWait-Waiter" + i, i) {
                    @Override
                    public void testBody() {

                        int waiterNumber =
                            ((MultiWaiterTestThread)
                             Thread.currentThread()).waiterNumber;

                        /* Wait for tester1 to start. */
                        debugMsg("Waiting for main to acquire latch");

                        while (latch1.owner() != tester1) {
                            Thread.yield();
                        }

                        /*
                         * Wait until it's our turn to try to acquire the
                         * latch.
                         */
                        debugMsg("Waiting for our turn to acquire latch");
                        while (latch1.nWaiters() < waiterNumber) {
                            Thread.yield();
                        }

                        debugMsg("About to acquire latch");
                        /* Try to acquire the latch */
                        try {
                            latch1.acquire();
                        } catch (DatabaseException LE) {
                            fail("caught DatabaseException");
                        }

                        debugMsg("nWaiters: " + latch1.nWaiters());
                        assertTrue(latch1.nWaiters() ==
                                   (N_WAITERS - waiterNumber - 1));

                        debugMsg("About to release latch");
                        /* Release it. */
                        try {
                            latch1.release();
                        } catch (EnvironmentFailureException e) {
                            fail("unexpected " + e);
                        }
                    }
                };
        }

        tester1.start();

        for (int i = 0; i < N_WAITERS; i++) {
            waiterThreads[i].start();
        }

        tester1.finishTest();
        for (int i = 0; i < N_WAITERS; i++) {
            waiterThreads[i].finishTest();
        }
    }
}
