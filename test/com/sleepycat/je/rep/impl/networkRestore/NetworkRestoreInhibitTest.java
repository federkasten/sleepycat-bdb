/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002-2014 Oracle.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.impl.networkRestore;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sleepycat.je.rep.InsufficientLogException;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.impl.RepParams;
import com.sleepycat.je.rep.impl.RepTestBase;
import com.sleepycat.je.rep.utilint.RepTestUtils;

/**
 * Test to verify that MIN_RETAINED_VLSNS inhibits ILEs.
 */
public class NetworkRestoreInhibitTest extends RepTestBase {

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Demonstrate sr 22782, a scenario in which there is an unnecessary ILE.
     *
     * 1) Create a 3 node group and populate it.
     *
     * 2) Shut down rn3
     *
     * 3) make further mods
     *
     * 4) Alter the rep stream timeout to a small interval (10 s)
     *
     * 5) Close rn1 and rn2 so all members of the group are quiescent and sleep
     * for this interval
     *
     * 6) Open rn1 and rn2
     *
     * 7) Open rn3 and observe the ILE. This is a spurious ILE because rn1 and
     * rn2 do indeed have sufficient log to adequately feed rn3. The problem is
     * that when rn1 and rn2 rejoin the group, they jointly calculate a cbvlsn
     * that excludes rn3's endpoint, because (a) rn3 was not alive during the
     * negotiations, and (b) rn3's local cbvlsn entry in the group database is
     * considered out of date, and is ignored.
     */

    @Test
    public void testUnnecessaryILE() throws InterruptedException {
        setRepConfigParam(RepParams.MIN_RETAINED_VLSNS, "0");
        setupTimedOutEnvironment();

        try {
            repEnvInfo[2].openEnv();
            fail("expected ILE demonstrating sr22782");
        } catch (InsufficientLogException ile) {
            /* Expected ILE to demonstrate sr22782 */
        }
    }

    /**
     * Demonstrate the fix for sr 22782. Note that this fix should be
     * unnecessary, once we move on to the "real fix" of retaining log files
     * based upon disk availability, rather than the global CBVLSN.
     *
     * Run the above test, but with MIN_RETAINED_VLSNS defaulted. The default
     * should be sufficient to avoid an ILE.
     */
    @Test
    public void testNoILE() throws InterruptedException {
        /*
         * Default the MIN_RETAINED_VLSNS.
         */
        setupTimedOutEnvironment();

        /* No ILE, a normal syncup */
        repEnvInfo[2].openEnv();

        /* Verifies that they are all at the same VLSN. */
        RepTestUtils.syncGroup(repEnvInfo);
    }

    /**
     * Run steps 1-6 described above.
     */
    private void setupTimedOutEnvironment()
        throws InterruptedException {

        createGroup(3);
        ReplicatedEnvironment master = repEnvInfo[0].getEnv();
        assertTrue(master.getState().isMaster());

        populateDB(repEnvInfo[0].getEnv(), "testDB", 1000);
        repEnvInfo[2].closeEnv();

        /*
         * 10 additional changes that rn3 will need to sync up when it
         * joins the group
         */
        populateDB(repEnvInfo[0].getEnv(), "testDB", 10);
        RepTestUtils.syncGroup(repEnvInfo);
        closeNodes(repEnvInfo[0], repEnvInfo[1]);

        /*
         * Shorten the rep stream timeout to eliminate rn3 from the global
         * cbvlsn computation.
         */
        final int streamTimeoutMs = 10000;
        setRepConfigParam(RepParams.REP_STREAM_TIMEOUT,
                          streamTimeoutMs + " ms");

        Thread.sleep(streamTimeoutMs + 10);

        restartNodes(repEnvInfo[0], repEnvInfo[1]);
    }
}
