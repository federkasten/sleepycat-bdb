/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2004, 2010 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.recovery;

import java.io.File;

import org.junit.Test;

import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

public class MultiEnvTest extends TestBase {

    private final File envHome1;
    private final File envHome2;

    public MultiEnvTest() {
        envHome1 = SharedTestUtils.getTestDir();
        envHome2 = new File(envHome1,
                            "propTest");
    }

    @Test
    public void testNodeIdsAfterRecovery() {

            /*
             * TODO: replace this test which previously checked that the node
             * id sequence shared among environments was correct with a test
             * that checks all sequences, including replicated ones. This
             * change is appropriate because the node id sequence is no longer
             * a static field.
             */
    }
}
