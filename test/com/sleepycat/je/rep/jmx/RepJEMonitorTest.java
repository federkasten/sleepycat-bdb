/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.jmx;

import java.io.File;

import javax.management.DynamicMBean;

import org.junit.After;

import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.util.test.SharedTestUtils;

/**
 * Test RepJEMonitor.
 */
public class RepJEMonitorTest extends com.sleepycat.je.jmx.JEMonitorTest {
    private static final boolean DEBUG = false;
    private final File envRoot;
    private RepEnvInfo[] repEnvInfo;

    public RepJEMonitorTest() {
        envRoot = SharedTestUtils.getTestDir();
    }

    @After
    public void tearDown()
        throws Exception {

        RepTestUtils.shutdownRepEnvs(repEnvInfo);
    }

    @Override
    protected DynamicMBean createMBean(Environment env) {
        return new RepJEMonitor(env);
    }

    @Override
    protected Environment openEnv(boolean openTransactionally)
        throws Exception {

        EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(openTransactionally);

        repEnvInfo = RepTestUtils.setupEnvInfos(envRoot, 2, envConfig);
        ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);

        return master;
    }
}
