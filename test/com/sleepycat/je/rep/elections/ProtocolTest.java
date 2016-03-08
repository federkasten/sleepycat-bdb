/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */
package com.sleepycat.je.rep.elections;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.After;
import org.junit.Before;

import com.sleepycat.je.JEVersion;
import com.sleepycat.je.log.LogEntryType;
import com.sleepycat.je.rep.elections.Proposer.Proposal;
import com.sleepycat.je.rep.elections.Protocol.StringValue;
import com.sleepycat.je.rep.elections.Protocol.Value;
import com.sleepycat.je.rep.elections.Protocol.ValueParser;
import com.sleepycat.je.rep.impl.TextProtocol;
import com.sleepycat.je.rep.impl.TextProtocol.Message;
import com.sleepycat.je.rep.impl.TextProtocolTestBase;
import com.sleepycat.je.rep.impl.node.NameIdPair;

public class ProtocolTest extends TextProtocolTestBase {

    private Protocol protocol = null;

    @Before
    public void setUp() 
        throws Exception {

        protocol = new Protocol(TimebasedProposalGenerator.getParser(),
                                new ValueParser() {
                                    public Value parse(String wireFormat) {
                                        if ("".equals(wireFormat)) {
                                            return null;
                                        }
                                        return new StringValue(wireFormat);

                                    }
                                },
                                GROUP_NAME,
                                new NameIdPair(NODE_NAME, 1),
                                null);
        protocol.updateNodeIds(new HashSet<Integer>
                               (Arrays.asList(new Integer(1))));
    }

    @After
    public void tearDown() {
        protocol = null;
    }

    @Override
    protected Message[] createMessages() {
        TimebasedProposalGenerator proposalGenerator =
            new TimebasedProposalGenerator();
        Proposal proposal = proposalGenerator.nextProposal();
        Value value = new Protocol.StringValue("test1");
        Value svalue = new Protocol.StringValue("test2");
        Message[] messages = new Message[] {
                protocol.new Propose(proposal),
                protocol.new Accept(proposal, value),
                protocol.new Result(proposal, value),
                protocol.new Shutdown(),
                protocol.new MasterQuery(),

                protocol.new Reject(proposal),
                protocol.new Promise(proposal, value, svalue, 100, 1,
                                     LogEntryType.LOG_VERSION,
                                     JEVersion.CURRENT_VERSION),
                protocol.new Accepted(proposal, value),
                protocol.new MasterQueryResponse(proposal, value)
        };

        return messages;
    }

    @Override
    protected TextProtocol getProtocol() {
        return protocol;
    }
}
