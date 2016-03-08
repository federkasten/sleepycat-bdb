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

public class ValidStateListener implements StateChangeListener {
    CountDownLatch waitForValidState = new CountDownLatch(1);

    public void stateChange(StateChangeEvent stateChangeEvent) {
        if (stateChangeEvent.getState().isActive()) {
            waitForValidState.countDown();
        }
    }

    public void awaitValidState()
        throws InterruptedException {

        waitForValidState.await();
    }
}
