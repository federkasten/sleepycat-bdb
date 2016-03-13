/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.impl.node;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.sleepycat.je.CommitToken;
import com.sleepycat.je.rep.GroupShutdownException;
import com.sleepycat.je.rep.NoConsistencyRequiredPolicy;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.impl.RepTestBase;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;

public class GroupShutdownTest extends RepTestBase {

    @Test
    public void testShutdownExceptions() {
        createGroup();
        ReplicatedEnvironment mrep = repEnvInfo[0].getEnv();

        try {
            repEnvInfo[1].getEnv().shutdownGroup(10000, TimeUnit.MILLISECONDS);
            fail("expected exception");
        } catch (IllegalStateException e) {
            /* OK, shutdownGroup on Replica. */
        }

        ReplicatedEnvironment mrep2 =
            new ReplicatedEnvironment(repEnvInfo[0].getEnvHome(),
                                      repEnvInfo[0].getRepConfig(),
                                      repEnvInfo[0].getEnvConfig());

        try {
            mrep.shutdownGroup(10000, TimeUnit.MILLISECONDS);
            fail("expected exception");
        } catch (IllegalStateException e) {
            /* OK, multiple master handles. */
            mrep2.close();
        }
        mrep.shutdownGroup(10000, TimeUnit.MILLISECONDS);
        for (int i=1; i < repEnvInfo.length; i++) {
            repEnvInfo[i].closeEnv();
        }
    }

    @Test
    public void testShutdownTimeout()
        throws InterruptedException {

        new ShutdownSupport() {
            @Override
            void checkException(GroupShutdownException e){}
        }.shutdownBasic(500, 1);
    }

    @Test
    public void testShutdownBasic()
        throws InterruptedException {

        new ShutdownSupport() {
            @Override
            void checkException(GroupShutdownException e) {
                /*
                 * It's possible, in rare circumstances, for the exception to
                 * not contain the shutdown VLSN, that is, for it to be null,
                 * because the VLSNIndex range was not yet initialized. Ignore
                 * it in that circumstance.
                 */
                assertTrue((e.getShutdownVLSN() == null) ||
                           (ct.getVLSN() <=
                            e.getShutdownVLSN().getSequence()));
            }
        }.shutdownBasic(10000, 0);
    }

    abstract class ShutdownSupport {
        CommitToken ct;

        abstract void checkException(GroupShutdownException e);

        public void shutdownBasic(long timeoutMs,
                                  int testDelayMs)
            throws InterruptedException {

            createGroup();
            ReplicatedEnvironment mrep = repEnvInfo[0].getEnv();
            leaveGroupAllButMaster();

            ct = populateDB(mrep, TEST_DB_NAME, 1000);
            repEnvInfo[0].getRepNode().feederManager().
                setTestDelayMs(testDelayMs);
            restartReplicasNoWait();

            mrep.shutdownGroup(timeoutMs, TimeUnit.MILLISECONDS);

            for (int i=1; i < repEnvInfo.length; i++) {
                RepEnvInfo repi = repEnvInfo[i];
                final int retries = 100;
                for (int j=0; j < retries; j++) {
                    try {
                        /* Provoke exception */
                        repi.getEnv().getState();
                        if ((j+1) == retries) {
                            fail("expected exception from " +
                                 repi.getRepNode().getNameIdPair());
                        }
                        /* Give the replica time to react */
                        Thread.sleep(1000); /* a second between retries */
                    } catch (GroupShutdownException e) {
                        checkException(e);
                        break;
                    }
                }
                /* Close the handle. */
                repi.closeEnv();
            }
        }
    }

    /**
     * Start up replicas for existing master, but don't wait for any
     * consistency to be reached.
     */
    private void restartReplicasNoWait() {
        for (int i=1; i < repEnvInfo.length; i++) {
            RepEnvInfo ri = repEnvInfo[i];
            ri.openEnv(new NoConsistencyRequiredPolicy());
        }
    }
}
