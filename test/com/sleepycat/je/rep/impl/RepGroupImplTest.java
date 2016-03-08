/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */
package com.sleepycat.je.rep.impl;

import static org.junit.Assert.assertEquals;

import java.net.UnknownHostException;

import org.junit.Test;

import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.util.test.TestBase;

public class RepGroupImplTest extends TestBase {

    @Test
    public void testSerializeDeserialize()
        throws UnknownHostException {

        int electablePeers = 5;
        int learners = 1;
        RepGroupImpl group = RepTestUtils.createTestRepGroup(5, 1);
        String s1 = group.serializeHex();
        String tokens[] = s1.split(TextProtocol.SEPARATOR_REGEXP);
        assertEquals(1 /* The Res group itself */ +
                     electablePeers + learners, /* the individual nodes. */
                     tokens.length);
        RepGroupImpl dgroup = RepGroupImpl.deserializeHex(tokens, 0);
        assertEquals(group, dgroup);
        String s2 = dgroup.serializeHex();
        assertEquals(s1,s2);
    }
}
