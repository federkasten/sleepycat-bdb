/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 */
package com.sleepycat.je.rep.dual.trigger;

import static org.junit.Assert.fail;

import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sleepycat.je.Environment;
import com.sleepycat.je.trigger.Trigger;

public class ConfigTest extends com.sleepycat.je.trigger.ConfigTest {

    Environment env;

    @Before
    public void setUp() 
       throws Exception {

        super.setUp();
        env = create(envRoot, envConfig);
    }

    @After
    public void tearDown() 
        throws Exception {
        
        close(env);
        super.tearDown();
    }

    @Test
    public void testTriggerConfigOnEnvOpen() {
        dbConfig.setTriggers(Arrays.asList((Trigger) new InvokeTest.DBT("t1"),
                             (Trigger) new InvokeTest.DBT("t2")));

        /* Implementing ReplicatedDatabaseTrigger (RDBT) is expected. */
        try {
            env.openDatabase(null, "db1", dbConfig).close();
            fail("IAE expected");
        } catch (IllegalArgumentException iae) {
            // expected
        }

    }
}
