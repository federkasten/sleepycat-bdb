/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002,2008 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

import com.sleepycat.je.rep.impl.RepTestBase;
import com.sleepycat.je.rep.monitor.Monitor;
import com.sleepycat.je.rep.monitor.MonitorConfig;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;

public class ReplicationGroupTest extends RepTestBase {

    @SuppressWarnings("null")
    @Test
    public void testBasic()
        throws InterruptedException {

        int electableNodeSize = groupSize-1;
        createGroup(electableNodeSize);
                
        ReplicationConfig rConfig = repEnvInfo[groupSize-1].getRepConfig();
        rConfig.setNodeType(NodeType.MONITOR);
        MonitorConfig monConfig = new MonitorConfig();
        monConfig.setNodeName(rConfig.getNodeName());
        monConfig.setGroupName(rConfig.getGroupName());
        monConfig.setNodeHostPort(rConfig.getNodeHostPort());
        monConfig.setHelperHosts(rConfig.getHelperHosts());
        
        new Monitor(monConfig).register();

        for (int i=0; i < electableNodeSize; i++) {
            ReplicatedEnvironment env = repEnvInfo[i].getEnv();
            ReplicationGroup group = null;
            for (int j=0; j < 100; j++) {
                group = env.getGroup();
                if (group.getNodes().size() == groupSize) {
                    break;
                }
                /* Wait for the replica to catch up. */
                Thread.sleep(1000);
            }
            assertEquals(groupSize, group.getNodes().size());
            assertEquals(RepTestUtils.TEST_REP_GROUP_NAME, group.getName());

            for (RepEnvInfo rinfo : repEnvInfo) {
                final ReplicationConfig repConfig = rinfo.getRepConfig();
                ReplicationNode member =
                    group.getMember(repConfig.getNodeName());
                assertTrue(member != null);
                assertEquals(repConfig.getNodeName(), member.getName());
                assertEquals(repConfig.getNodeType(), member.getType());
                assertEquals(repConfig.getNodeSocketAddress(),
                             member.getSocketAddress());
            }

            Set<ReplicationNode> electableNodes = group.getElectableNodes();
            assertEquals(electableNodeSize, electableNodes.size());
            for (ReplicationNode n : electableNodes) {
                assertEquals(NodeType.ELECTABLE, n.getType());
            }

            final Set<ReplicationNode> monitorNodes = group.getMonitorNodes();
            for (ReplicationNode n : monitorNodes) {
                assertEquals(NodeType.MONITOR, n.getType());
            }
            assertEquals(1, monitorNodes.size());

            assertEquals(groupSize, group.getNodes().size());
        }
    }
}
