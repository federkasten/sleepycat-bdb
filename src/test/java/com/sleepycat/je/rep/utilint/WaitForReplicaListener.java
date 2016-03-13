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

public class WaitForReplicaListener implements StateChangeListener {
    CountDownLatch waitForReplica = new CountDownLatch(1);
    private boolean success = true;

    @Override
    public void stateChange(StateChangeEvent stateChangeEvent) {
        if (stateChangeEvent.getState().isReplica()) {
            waitForReplica.countDown();
        }
        if (stateChangeEvent.getState().isDetached()) {
            /* It will never return to the replica state. */
            success = false;
            waitForReplica.countDown();
        }
    }

    public boolean awaitReplica()
        throws InterruptedException {

        waitForReplica.await();
        return success;
    }
}
