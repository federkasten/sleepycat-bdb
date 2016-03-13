/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.utilint;

import java.util.concurrent.CountDownLatch;

import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.StateChangeEvent;
import com.sleepycat.je.rep.StateChangeListener;

public class WaitForMasterListener implements StateChangeListener {
    CountDownLatch waitForMaster = new CountDownLatch(1);
    private boolean success = true;

    public void stateChange(StateChangeEvent stateChangeEvent) {
        if (stateChangeEvent.getState().equals
            (ReplicatedEnvironment.State.MASTER)) {
            waitForMaster.countDown();
        }

        if (stateChangeEvent.getState().isDetached()) {
            /* It will never return to the replica state. */
            success = false;
            waitForMaster.countDown();
        }
    }

    public boolean awaitMastership()
        throws InterruptedException {

        waitForMaster.await();
        return success;
    }
}
