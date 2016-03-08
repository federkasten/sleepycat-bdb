/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.elections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sleepycat.je.rep.elections.Proposer.Proposal;
import com.sleepycat.je.rep.elections.Protocol.Accept;
import com.sleepycat.je.rep.elections.Protocol.Propose;
import com.sleepycat.je.rep.elections.Protocol.StringValue;
import com.sleepycat.je.rep.elections.Protocol.Value;
import com.sleepycat.je.rep.impl.TextProtocol.ResponseMessage;
import com.sleepycat.je.rep.impl.node.NameIdPair;
import com.sleepycat.je.rep.impl.node.RepNode;
import com.sleepycat.util.test.TestBase;

/**
 * Tests the Acceptor Protocol for the correct responses to Propose and Accept
 * messages, based on the Paxos protocol.
 */
public class AcceptorTest extends TestBase {

    Protocol protocol;
    Acceptor acceptor;

    TimebasedProposalGenerator proposalGenerator =
        new TimebasedProposalGenerator();

    @Before
    public void setUp() {
        Acceptor.SuggestionGenerator suggestionGenerator =
            new Acceptor.SuggestionGenerator() {

            @SuppressWarnings("unused")
            public Value get(Proposal proposal) {
                return new StringValue("VALUE");
            }

            @SuppressWarnings("unused")
            public long getRanking(Proposal proposal) {
                return 100;
            }
        };
        protocol = new Protocol
        (TimebasedProposalGenerator.getParser(),
         MasterValue.getParser(),
         "TestGroup",
         new NameIdPair("n1", 1),
         null);
        protocol.updateNodeIds(new HashSet<Integer>
                                (Arrays.asList(Integer.valueOf(1))));
        acceptor = new Acceptor
            (protocol,
             new RepNode(new NameIdPair("n0", 0)) {
                @Override
                public int getElectionPriority() {
                    return 1;
                }
            },
            suggestionGenerator);
    }

    @After
    public void tearDown() {
        acceptor = null;
    }

    void checkPropose(Proposal pn, Protocol.MessageOp checkOp) {
        Propose prop = protocol.new Propose(pn);
        ResponseMessage prom1 = acceptor.process(prop);

        assertEquals(checkOp, prom1.getOp());
    }

    void checkAccept(Proposal pn, Value v, Protocol.MessageOp checkOp) {
        Accept a = protocol.new Accept(pn, v);
        ResponseMessage ad = acceptor.process(a);
        assertEquals(checkOp, ad.getOp());
    }

    @Test
    public void testAcceptor() {
        Proposal pn0 = proposalGenerator.nextProposal();
        Proposal pn1 = proposalGenerator.nextProposal();

        /* Proposal numbers should be in ascending order. */
        assertTrue(pn1.compareTo(pn0)> 0);

        checkPropose(pn1, protocol.PROMISE);

        /* Lower numbered proposal should be rejected. */
        checkPropose(pn0, protocol.REJECT);

        Value v = new StringValue("VALUE");
        checkAccept(pn1, v, protocol.ACCEPTED);

        /* .. and continue to be rejected after the acceptance. */
        checkPropose(pn0, protocol.REJECT);

        /* .. higher proposals should still be accepted. */
        Proposal pn2 = proposalGenerator.nextProposal();
        assertTrue(pn2.compareTo(pn1)> 0);
        checkPropose(pn2, protocol.PROMISE);
        checkAccept(pn2, v, protocol.ACCEPTED);

        /* .. and ones lower than the promised proposal are rejected. */
        checkAccept(pn0, v, protocol.REJECT);
        checkAccept(pn1, v, protocol.REJECT);
    }
}
