/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2004, 2010 Oracle and/or its affiliates.  All rights reserved.
 *
 */
package com.sleepycat.je.rep.util;

import java.net.InetSocketAddress;

import org.junit.After;
import org.junit.Before;

import com.sleepycat.je.rep.utilint.ServiceDispatcher;
import com.sleepycat.util.test.TestBase;

public abstract class ServiceDispatcherTestBase extends TestBase {

    protected ServiceDispatcher dispatcher = null;
    private static final int TEST_PORT = 5000;
    protected InetSocketAddress dispatcherAddress;

    @Before
    public void setUp() 
        throws Exception {
        
        super.setUp();
        dispatcherAddress = new InetSocketAddress("localhost", TEST_PORT);
        dispatcher = new ServiceDispatcher(dispatcherAddress);
        dispatcher.start();
    }

    @After
    public void tearDown() 
        throws Exception {
        
        dispatcher.shutdown();
        dispatcher = null;
    }
}
