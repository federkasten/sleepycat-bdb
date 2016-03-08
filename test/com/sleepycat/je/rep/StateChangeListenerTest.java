/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002,2008 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep;

import static com.sleepycat.je.rep.ReplicatedEnvironment.State.DETACHED;
import static com.sleepycat.je.rep.ReplicatedEnvironment.State.MASTER;
import static com.sleepycat.je.rep.ReplicatedEnvironment.State.REPLICA;
import static com.sleepycat.je.rep.ReplicatedEnvironment.State.UNKNOWN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.rep.ReplicatedEnvironment.State;
import com.sleepycat.je.rep.impl.RepTestBase;

public class StateChangeListenerTest extends RepTestBase {

    private CountDownLatch  listenerLatch = null;

    /*
     * Verify that a ReplicaStateException is correctly associated with the
     * state change event that established it as such.
     */
    @Test
    public void testEventIdentity() {
        ReplicatedEnvironment rep0 = repEnvInfo[0].openEnv();
        rep0.setStateChangeListener(new PassiveListener(rep0));

        ReplicatedEnvironment rep1 = repEnvInfo[1].openEnv();
        rep1.setStateChangeListener(new PassiveListener(rep1));
        assertTrue(rep1.getState().isReplica());
        try {
            rep1.openDatabase(null,"db", dbconfig);
            fail("expected exception");
        } catch (ReplicaWriteException e) {
            final PassiveListener passiveListener =
                (PassiveListener)rep1.getStateChangeListener();
            assertEquals(e.getEvent(), passiveListener.currentEvent);
        }
    }

    /*
     * Verify that an exception leaking out of a listener invalidates the
     * environment.
     */
    @Test
    public void testExceptionInStateChangeNotifier() {
        ReplicatedEnvironment rep = repEnvInfo[0].openEnv();
        BadListener listener = new BadListener();
        try {
            rep.setStateChangeListener(listener);
            fail("Expected exception");
        } catch (EnvironmentFailureException e) {
            assertTrue(e.getCause() instanceof NullPointerException);
            assertTrue(!rep.isValid());
        }
        repEnvInfo[0].closeEnv();
    }

    @Test
    public void testListenerReplacement() {
        ReplicatedEnvironment rep = repEnvInfo[0].openEnv();

        final Listener listener1 = new Listener(rep);
        rep.setStateChangeListener(listener1);
        assertEquals(listener1, rep.getStateChangeListener());
        final Listener listener2 = new Listener(rep);
        rep.setStateChangeListener(listener2);
        assertEquals(listener2, rep.getStateChangeListener());
        repEnvInfo[0].closeEnv();
    }

    @Test
    public void testBasic()
        throws Exception {
        List<Listener> listeners = new LinkedList<Listener>();

        /* Verify that initial notification is always sent. */
        for (int i=0; i < repEnvInfo.length; i++) {
            ReplicatedEnvironment rep = repEnvInfo[i].openEnv();
            State state = rep.getState();
            State expectedState = (i == 0) ? MASTER : REPLICA;
            assertEquals(expectedState, state);
            Listener listener = new Listener(rep);
            listeners.add(listener);
            rep.setStateChangeListener(listener);
            /* Check that there was an immediate callback. */
            assertEquals(1, listener.events.size());
            StateChangeEvent event = listener.events.get(0);
            assertEquals(expectedState, event.getState());
            assertEquals(repEnvInfo[0].getRepConfig().getNodeName(),
                         event.getMasterNodeName());
            listener.events.clear();
        }

        /* 
         * Verify that notifications are sent on master transitions. 2 
         * transitions per node, except for the node being shutdown. 
         */
        listenerLatch = new CountDownLatch(repEnvInfo.length*2);
        repEnvInfo[0].closeEnv();
        boolean done = listenerLatch.await(30, TimeUnit.SECONDS);
        assertTrue(done);
        
        assertEquals(2, listeners.get(0).events.size());
        assertEquals(UNKNOWN, listeners.get(0).events.get(0).getState());
        assertEquals(DETACHED, listeners.get(0).events.get(1).getState());

        int masterIndex = -1;
        for (int i=1; i < repEnvInfo.length; i++) {
            /* Verify state transitions: UNKNOWN [MASTER | REPLICA] */
            assertEquals(2, listeners.get(i).events.size());

            final State handleState = repEnvInfo[i].getEnv().getState();
            assertEquals(UNKNOWN, listeners.get(i).events.get(0).getState());
            assertEquals(handleState,
                         listeners.get(i).events.get(1).getState());
            if (handleState == MASTER) {
                masterIndex = i;
            }
        }
        assertTrue(masterIndex > 0);

        /* Verify that notifications are sent on close. */
        for (int i=1; i < repEnvInfo.length; i++) {
            listeners.get(i).events.clear();
            int numExpectedEvents = (masterIndex==i) ? 2 : 1;
            listenerLatch = new CountDownLatch(numExpectedEvents);
            repEnvInfo[i].closeEnv();
            done = listenerLatch.await(30, TimeUnit.SECONDS);
            assertTrue(done);
            assertEquals(numExpectedEvents, listeners.get(i).events.size());
        }
    }

    class Listener implements StateChangeListener {

        final ReplicatedEnvironment rep;
        List<StateChangeEvent> events = new LinkedList<StateChangeEvent>();

        public Listener(ReplicatedEnvironment rep) {
            this.rep = rep;
        }

        @Override
        public void stateChange(StateChangeEvent stateChangeEvent) {
            events.add(stateChangeEvent);
            if (listenerLatch != null) {
                listenerLatch.countDown();
            }
        }
    }

    /* Always throw an exception upon notification. */
    class BadListener implements StateChangeListener {

        @Override
        public void stateChange
            (@SuppressWarnings("unused") StateChangeEvent stateChangeEvent) {

            throw new NullPointerException("Test exception");
        }
    }

    /**
     * A passive listener that simply remembers the last event.
     */
    class PassiveListener implements StateChangeListener {

        final ReplicatedEnvironment rep;
        StateChangeEvent currentEvent = null;

        public PassiveListener(ReplicatedEnvironment rep) {
            this.rep = rep;
        }

        @Override
        public void stateChange(StateChangeEvent stateChangeEvent) {
            currentEvent = stateChangeEvent;
        }
    }
}
