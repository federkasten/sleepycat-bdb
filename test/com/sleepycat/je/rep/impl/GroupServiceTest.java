/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */
package com.sleepycat.je.rep.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;

import java.net.InetSocketAddress;
import java.util.Set;

import org.junit.Test;

import com.sleepycat.je.rep.NodeType;
import com.sleepycat.je.rep.RepInternal;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.impl.RepGroupProtocol.EnsureOK;
import com.sleepycat.je.rep.impl.RepGroupProtocol.Fail;
import com.sleepycat.je.rep.impl.RepGroupProtocol.FailReason;
import com.sleepycat.je.rep.impl.RepGroupProtocol.GroupResponse;
import com.sleepycat.je.rep.impl.TextProtocol.MessageExchange;
import com.sleepycat.je.rep.impl.TextProtocol.OK;
import com.sleepycat.je.rep.impl.TextProtocol.ResponseMessage;
import com.sleepycat.je.rep.impl.node.NameIdPair;
import com.sleepycat.je.rep.impl.node.RepNode;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.rep.utilint.ServiceDispatcher;

public class GroupServiceTest extends RepTestBase {

    @SuppressWarnings("null")
    @Test
    public void testService() throws Exception {
        RepTestUtils.joinGroup(repEnvInfo);
        RepNode master = null;
        ServiceDispatcher masterDispatcher = null;
        for (RepEnvInfo repi : repEnvInfo) {
            ReplicatedEnvironment replicator = repi.getEnv();
            RepNode repNode =
                RepInternal.getRepImpl(replicator).getRepNode();
            ServiceDispatcher dispatcher = repNode.getServiceDispatcher();
            if (repNode.isMaster()) {
                master = repNode;
                masterDispatcher = dispatcher;
            }
        }
        assertTrue(masterDispatcher != null);
        InetSocketAddress socketAddress = masterDispatcher.getSocketAddress();
        RepGroupProtocol protocol =
            new RepGroupProtocol(RepTestUtils.TEST_REP_GROUP_NAME,
                                 NameIdPair.NULL,
                                 master.getRepImpl());

        /* Test Group Request. */
        MessageExchange me =
            protocol.new MessageExchange(socketAddress,
                                         GroupService.SERVICE_NAME,
                                         protocol.new GroupRequest());
        me.run();
        ResponseMessage resp = me.getResponseMessage();
        assertEquals(GroupResponse.class, resp.getClass());
        assertEquals(master.getGroup(), ((GroupResponse)resp).getGroup());
        int monitorCount =
            ((GroupResponse)resp).getGroup().getMonitorNodes().size();

        /* Test add Monitor. */
        short monitorId = 1000;
        RepNodeImpl monitor =
            new RepNodeImpl(new NameIdPair("mon"+monitorId, monitorId),
                              NodeType.MONITOR, "localhost", 6000);
        me = protocol.new MessageExchange(socketAddress,
                                          GroupService.SERVICE_NAME,
                                          protocol.new EnsureNode(monitor));
        me.run();
        resp = me.getResponseMessage();
        assertEquals(EnsureOK.class, resp.getClass());


        /* Retrieve the group again, it should have the new monitor. */
        me = protocol.new MessageExchange(socketAddress,
                                          GroupService.SERVICE_NAME,
                                          protocol.new GroupRequest());
        me.run();
        resp = me.getResponseMessage();
        assertEquals(GroupResponse.class, resp.getClass());
        RepGroupImpl repGroup = ((GroupResponse)resp).getGroup();
        Set<RepNodeImpl> monitors = repGroup.getMonitorNodes();
        assertEquals(monitorCount+1, monitors.size());

        /* Exercise the remove member service to remove the monitor. */
        me = protocol.new MessageExchange
        (socketAddress,GroupService.SERVICE_NAME,
         protocol.new RemoveMember(monitor.getName()));
        me.run();
        resp = me.getResponseMessage();
        assertEquals(OK.class, resp.getClass());

        /* Retrieve the group again and check for the absence of the monitor */
        me = protocol.new MessageExchange(socketAddress,
                                          GroupService.SERVICE_NAME,
                                          protocol.new GroupRequest());
        me.run();
        resp = me.getResponseMessage();
        assertEquals(GroupResponse.class, resp.getClass());
        repGroup = ((GroupResponse)resp).getGroup();
        monitors = repGroup.getMonitorNodes();
        assertEquals(0, monitors.size());

        /*
         * Most GroupService requests can only be served by the master.  See
         * that requests sent to a replica are rejected.
         */
        RepEnvInfo deadNode = repEnvInfo[4];
        assertTrue(deadNode.isReplica());
        deadNode.closeEnv();
        RepEnvInfo replica = repEnvInfo[1];
        assertTrue(replica.isReplica());
        socketAddress = replica.getRepConfig().getNodeSocketAddress();
        RepNode repNode = RepInternal.getRepImpl(replica.getEnv()).getRepNode();
        protocol = new RepGroupProtocol(RepTestUtils.TEST_REP_GROUP_NAME,
                                        NameIdPair.NULL,
                                        repNode.getRepImpl());
        me = protocol.new MessageExchange
             (socketAddress, GroupService.SERVICE_NAME,
              protocol.new RemoveMember(deadNode.getRepConfig().getNodeName()));
        me.run();
        resp = me.getResponseMessage();
        assertEquals(Fail.class, resp.getClass());
        Fail fail = (Fail) resp;
        assertSame(FailReason.IS_REPLICA, fail.getReason());

        /* Restart dead node, just to placate superclass tearDown(). */
        deadNode.openEnv();
    }
}
