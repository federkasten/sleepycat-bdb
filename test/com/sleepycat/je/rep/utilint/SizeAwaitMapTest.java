/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.utilint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.util.test.TestBase;

public class SizeAwaitMapTest extends TestBase {

    SizeAwaitMap<Integer, Integer> smap = null;
    SizeWaitThread testThreads[];
    AtomicInteger doneThreads;

    CountDownLatch startLatch = null;

    /* Large number to help expose concurrency issues, if any. */
    static final int threadCount = 200;

    @Before
    public void setUp() 
        throws Exception {
        
        super.setUp();
        smap = new SizeAwaitMap<Integer,Integer>
               (null,
                Collections.synchronizedMap(new HashMap<Integer,Integer>()));
        testThreads = new SizeWaitThread[threadCount];
        doneThreads = new AtomicInteger(0);
        startLatch = new CountDownLatch(threadCount);
        for (int i=0; i < threadCount; i++) {
            testThreads[i] =
                new SizeWaitThread(i, Long.MAX_VALUE, TimeUnit.MILLISECONDS);
            testThreads[i].start();
        }
        // Wait for threads to start up
        startLatch.await();
    }

    private void checkLiveThreads(int checkStart) {
        for (int j=checkStart; j < threadCount; j++) {
            assertTrue(testThreads[j].isAlive());
        }
        assertEquals(checkStart, doneThreads.intValue());
    }

    /**
     * Tests basic put/remove operations
     */
    @Test
    public void testBasic() throws InterruptedException {
        testThreads[0].join();
        assertEquals(1, doneThreads.intValue());
        for (int i=1; i < threadCount; i++) {
            assertTrue(testThreads[i].isAlive());
            smap.put(i, i);
            testThreads[i].join();
            assertTrue(testThreads[i].success);
            // All subsequent threads continue to live
            checkLiveThreads(i+1);

            // Remove should have no impact
            smap.remove(i);
            checkLiveThreads(i+1);

            // Re-adding should have no impact
            smap.put(i, i);
            checkLiveThreads(i+1);
        }
    }

    /*
     * Tests clear operation.
     */
    @Test
    public void testClear() throws InterruptedException {
        testThreads[0].join();
        assertEquals(1, doneThreads.intValue());
        /* Wait for the threads */
        while (smap.latchCount()!= (threadCount-1)) {
            Thread.sleep(10);
        }

        smap.clear(new MyTestException());
        assertTrue(smap.size() == 0);
        for (int i=1; i < threadCount; i++) {
            testThreads[i].join();
            assertTrue(testThreads[i].cleared);
            assertFalse(testThreads[i].interrupted);
        }
        assertEquals(threadCount, doneThreads.intValue());
    }

    /**
     * Threads which wait for specific map sizes.
     */
    private class SizeWaitThread extends Thread {

        /* The size to wait for. */
        final int size;
        final long timeout;
        final TimeUnit unit;
        boolean interrupted = false;
        boolean cleared = false;
        boolean success = false;

        SizeWaitThread(int size, long timeout, TimeUnit unit) {
            this.size = size;
            this.timeout = timeout;
            this.unit = unit;
        }

        public void run() {
            startLatch.countDown();
            try {
                success = smap.sizeAwait(size, timeout, unit);
            } catch (MyTestException mte) {
                cleared = true;
            } catch (InterruptedException e) {
                interrupted = true;
            } finally {
                doneThreads.incrementAndGet();
            }

        }
    }

    @SuppressWarnings("serial")
    private class MyTestException extends DatabaseException {
        MyTestException() {
            super("testing");
        }
    }
}
