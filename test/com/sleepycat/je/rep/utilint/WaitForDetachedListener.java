/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.utilint;

import java.util.concurrent.CountDownLatch;

import com.sleepycat.je.rep.StateChangeEvent;
import com.sleepycat.je.rep.StateChangeListener;

public class WaitForDetachedListener implements StateChangeListener {
    CountDownLatch waitForDetached = new CountDownLatch(1);
    private final boolean success = true;

    public void stateChange(StateChangeEvent stateChangeEvent) {
        if (stateChangeEvent.getState().isDetached()) {
            waitForDetached.countDown();
        }
    }

    public boolean awaitDetached()
        throws InterruptedException {

        waitForDetached.await();
        return success;
    }
}

