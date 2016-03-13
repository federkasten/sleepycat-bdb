/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */
package com.sleepycat.je.rep.monitor;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.After;
import org.junit.Before;

import com.sleepycat.je.rep.impl.RepGroupImpl;
import com.sleepycat.je.rep.impl.TextProtocol;
import com.sleepycat.je.rep.impl.TextProtocol.Message;
import com.sleepycat.je.rep.impl.TextProtocolTestBase;
import com.sleepycat.je.rep.impl.node.NameIdPair;
import com.sleepycat.je.rep.monitor.GroupChangeEvent.GroupChangeType;
import com.sleepycat.je.rep.monitor.LeaveGroupEvent.LeaveReason;

public class ProtocolTest extends TextProtocolTestBase {

    private Protocol protocol;

    @Before
    public void setUp() 
        throws Exception {

        super.setUp();
        protocol = 
            new Protocol(GROUP_NAME, new NameIdPair(NODE_NAME, 1), null);
        protocol.updateNodeIds(new HashSet<Integer>
                               (Arrays.asList(new Integer(1))));
    }

    @After
    public void tearDown() {
        protocol = null;
    }

    @Override 
    protected Message[] createMessages() {
        Message[] messages = new Message [] {
                protocol.new GroupChange(new RepGroupImpl(GROUP_NAME), 
                                         NODE_NAME, GroupChangeType.ADD),
                protocol.new JoinGroup(NODE_NAME, 
                                       null, 
                                       System.currentTimeMillis()),
                protocol.new LeaveGroup(NODE_NAME, null, 
                                        LeaveReason.ABNORMAL_TERMINATION,  
                                        System.currentTimeMillis(),
                                        System.currentTimeMillis())
        };

        return messages;
    }

    @Override
    protected TextProtocol getProtocol() {
        return protocol;
    }
}
