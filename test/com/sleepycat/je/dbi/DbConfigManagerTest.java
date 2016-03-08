/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.dbi;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.config.BooleanConfigParam;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.util.test.TestBase;

public class DbConfigManagerTest extends TestBase {

    /**
     * Test that parameter defaults work, that we can add and get
     * parameters
     */
    @Test
    public void testBasicParams() {
        EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setCacheSize(2000);
        DbConfigManager configManager = new DbConfigManager(envConfig);

        /**
         * Longs: The config manager should return the value for an
         * explicitly set param and the default for one not set.
         *
         */
        assertEquals(2000,
                     configManager.getLong(EnvironmentParams.MAX_MEMORY));
        assertEquals(EnvironmentParams.ENV_RECOVERY.getDefault(),
                     configManager.get(EnvironmentParams.ENV_RECOVERY));
    }

    /**
     * Checks that leading and trailing whitespace is ignored when parsing a
     * boolean.  [#22212]
     */
    @Test
    public void testBooleanWhitespace() {
        String val = " TruE "; // has leading and trailing space
        String name = EnvironmentConfig.SHARED_CACHE; // any boolean will do
        BooleanConfigParam param =
            (BooleanConfigParam) EnvironmentParams.SUPPORTED_PARAMS.get(name);
        param.validateValue(val);
        EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setConfigParam(name, val);
        DbConfigManager configManager = new DbConfigManager(envConfig);
        assertEquals(true, configManager.getBoolean(param));
    }
}
