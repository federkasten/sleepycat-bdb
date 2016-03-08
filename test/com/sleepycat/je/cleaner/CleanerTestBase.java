/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.cleaner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;

import com.sleepycat.je.Environment;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

public class CleanerTestBase extends TestBase {
    protected final File envHome;
    protected boolean envMultiSubDir;
    protected Environment env;
    protected static final int DATA_DIRS = 3;
    
    public CleanerTestBase() {
        envHome = SharedTestUtils.getTestDir();
    }
    
    public static List<Object[]> getEnv(boolean[] envMultiSubDirParams) {
        List<Object[]> list = new ArrayList<Object[]>();
        for (boolean env : envMultiSubDirParams)
            list.add(new Object[] { env });

        return list;
    }

    @Before
    public void setUp()
        throws Exception {

        super.setUp();
        if (envMultiSubDir) {
            TestUtils.createEnvHomeWithSubDir(envHome, DATA_DIRS);
        }
    }

    @After
    public void tearDown() 
        throws Exception {

        try {
            if (env != null) {
                env.close();
            }
        } catch (Throwable e) {
            System.out.println("tearDown: " + e);
        }
        env = null;
    }
}
