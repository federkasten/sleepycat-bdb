/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.dual;

import java.util.List;

import org.junit.runners.Parameterized.Parameters;

public class SecondaryTest extends com.sleepycat.je.test.SecondaryTest {

    public SecondaryTest(String type,
                         boolean multiKey,
                         boolean customAssociation){
        super(type, multiKey, customAssociation);
    }
    
    @Parameters
    public static List<Object[]> genParams() {
        return paramsHelper(true);
    }

    /**
     * Test is based on EnvironmentStats.getNLNsFetch which returns varied
     * results when replication is enabled, presumably because the feeder is
     * fetching.
     */
    @Override
    public void testImmutableSecondaryKey() {
    }

    /**
     * Same issue as testImmutableSecondaryKey.
     */
    @Override
    public void testExtractFromPrimaryKeyOnly() {
    }

    /**
     * Same issue as testImmutableSecondaryKey.
     */
    @Override
    public void testImmutableSecondaryKeyAndExtractFromPrimaryKeyOnly() {
    }
}
