/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */
package com.sleepycat.je.rep.impl;

import org.junit.Before;

import com.sleepycat.je.rep.ReplicatedEnvironment.State;
import com.sleepycat.je.rep.impl.TextProtocol.Message;
import com.sleepycat.je.rep.impl.node.NameIdPair;

/**
 * Tests the protocols used to querying the current state of a replica.
 */
public class NodeStateProtocolTest extends TextProtocolTestBase {

    private NodeStateProtocol protocol;

    @Before
    public void setUp() 
        throws Exception {

        super.setUp();
        protocol =
            new NodeStateProtocol(GROUP_NAME,
                                  new NameIdPair("n1", (short) 1),
                                  null);
    }

    @Override
    protected Message[] createMessages() {
        Message[] messages = new Message[] {
            protocol.new NodeStateRequest(NODE_NAME),
            protocol.new NodeStateResponse(NODE_NAME, 
                                           NODE_NAME,
                                           System.currentTimeMillis(),
                                           State.MASTER) 
        };

        return messages;
    }

    @Override
    protected TextProtocol getProtocol() {
        return protocol;
    }
}
