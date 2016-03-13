/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je;

import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Test parameter handling for api methods.
 */
public class ApiTest {

    @Test
    public void testBasic() {
        try {
            new Environment(null, null);
            fail("Should get exception");
        } catch (IllegalArgumentException e) {
            // expected exception
        } catch (Exception e) {
            fail("Shouldn't get other exception");
        }
    }
}
