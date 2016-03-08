/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2004, 2010 Oracle and/or its affiliates.  All rights reserved.
 *
 */
package com.sleepycat.je.rep.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.Test;

import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.rep.utilint.ServiceDispatcher;
import com.sleepycat.je.rep.utilint.ServiceDispatcher.Response;
import com.sleepycat.je.rep.utilint.ServiceDispatcher.ServiceConnectFailedException;

public class ServiceDispatcherTest extends ServiceDispatcherTestBase {

    /* The number of simulated services. */
    private static int numServices = 10;

    /* The simulated service map. */
    private static final Map<String, BlockingQueue<SocketChannel>> serviceMap =
        new HashMap<String, BlockingQueue<SocketChannel>>();

    /* Initialize the simulated service map. */
    static {
        for (int i=0; i < numServices; i++) {
            serviceMap.put("service"+i,
                           new LinkedBlockingQueue<SocketChannel>());
        }
    }

    class EService implements Runnable {
        final SocketChannel socketChannel;
        final int serviceNumber;

        EService(SocketChannel socketChannel, int serviceNumber) {
            this.socketChannel = socketChannel;
            this.serviceNumber = serviceNumber;
        }

        public void run() {
            try {
                socketChannel.configureBlocking(true);
                socketChannel.socket().getOutputStream().
                write((byte)serviceNumber);
                socketChannel.close();
            } catch (IOException e) {
                fail("Unexpected exception");
            }
        }
    }

    class LService extends Thread {

        final int serviceNumber;
        final BlockingQueue<SocketChannel> queue;

        LService(int serviceNumber, BlockingQueue<SocketChannel> queue) {
            this.queue = queue;
            this.serviceNumber = serviceNumber;
        }

        @Override
        public void run() {
            try {
                final SocketChannel channel = queue.take();
                channel.configureBlocking(true);
                channel.socket().getOutputStream(). write((byte)serviceNumber);
                channel.close();
            } catch (IOException e) {
                fail("Unexpected exception");
            } catch (InterruptedException e) {
                fail("Unexpected exception");
            }
        }
    }

    @Test
    public void testExecuteBasic() throws IOException {
        for (int i=0; i < numServices; i++) {
            final int serviceNumber = i;
            dispatcher.register
            (new ServiceDispatcher.ExecutingService("service"+i, dispatcher) {

                @Override
                public Runnable getRunnable(SocketChannel socketChannel) {
                    return new EService(socketChannel, serviceNumber);
                }
            });
        }

        verifyServices();
    }

    @Test
    public void testLazyQueueBasic() throws IOException {
        for (int i=0; i < numServices; i++) {
            LinkedBlockingQueue<SocketChannel> queue =
                new LinkedBlockingQueue<SocketChannel>();

            dispatcher.register
            (dispatcher.new LazyQueuingService
             ("service"+i, queue, new LService(i, queue)) {
            });
        }

        verifyServices();
    }

    /*
     * Verifies the services that were set up.
     */
    private void verifyServices() throws IOException {
        Socket sockets[] = new Socket[numServices];
        for (int i=0; i < numServices; i++) {
            Socket socket = new Socket();
            sockets[i] = socket;
            socket.connect(dispatcherAddress);

            try {
                @SuppressWarnings("unused")
                OutputStream out =
                 ServiceDispatcher.getServiceOutputStream(socket, "service"+i);
            } catch (ServiceConnectFailedException e1) {
                fail("Unexpected exception:" + e1.getMessage());
            }

        }

        for (int i=0; i < numServices; i++) {
            Socket socket = sockets[i];
            int result = socket.getInputStream().read();
            assertEquals(i,result);
            socket.close();
        }
    }

    @Test
    public void testBusyExecuteBasic() throws IOException {
        dispatcher.register
        (new ServiceDispatcher.ExecutingService("service1", dispatcher) {
            int bcount=0;
            @Override
            public Runnable getRunnable(SocketChannel socketChannel) {
                bcount++;
                return new EService(socketChannel, 1);
            }
            @Override
            public boolean isBusy() {
                return bcount > 0;
            }
        });

        Socket socket = new Socket();
        socket.connect(dispatcherAddress);

        try {
            @SuppressWarnings("unused")
            OutputStream out =
                ServiceDispatcher.getServiceOutputStream(socket, "service1");
        } catch (ServiceConnectFailedException e1) {
            fail("Unexpected exception:" + e1.getMessage());
        }

        /* Service should now be busy. */
        try {
            @SuppressWarnings("unused")
            OutputStream out =
                ServiceDispatcher.getServiceOutputStream(socket, "service1");
            fail("expected exception");
        } catch (ServiceConnectFailedException e1) {
            assertEquals(Response.BUSY, e1.getResponse());
        }
    }


    @Test
    public void testQueueBasic()
        throws IOException, InterruptedException {

        for (Entry<String, BlockingQueue<SocketChannel>> e :
            serviceMap.entrySet()) {
            dispatcher.register(e.getKey(), e.getValue());
        }

        for (Entry<String, BlockingQueue<SocketChannel>> e :
            serviceMap.entrySet()) {
            Socket socket = new Socket();
            socket.connect(dispatcherAddress);
            OutputStream out = null;
            try {
                out =
                  ServiceDispatcher.getServiceOutputStream(socket, e.getKey());
                out.close();
            } catch (ServiceConnectFailedException e1) {
                e1.printStackTrace();
                fail("Unexpected exception");
            }
        }

        for (Entry<String, BlockingQueue<SocketChannel>> e :
            serviceMap.entrySet()) {
            SocketChannel channel =
                dispatcher.takeChannel(e.getKey(), true, 100);
            assertTrue(channel != null);
            assertTrue(e.getValue().isEmpty());
        }
    }

    @Test
    public void testRegister() {
        try {
            dispatcher.register(null,
                                new LinkedBlockingQueue<SocketChannel>());
            fail("Expected EnvironmentFailureException");
        } catch(EnvironmentFailureException e) {
        }

        try {
            dispatcher.register("s1", (BlockingQueue<SocketChannel>)null);
            fail("Expected EnvironmentFailureException");
        } catch(EnvironmentFailureException e) {
        }

        dispatcher.register("s1", new LinkedBlockingQueue<SocketChannel>());
        try {
            dispatcher.register("s1",
                                new LinkedBlockingQueue<SocketChannel>());
            fail("Expected EnvironmentFailureException");
        } catch(EnvironmentFailureException e) {
        }
        dispatcher.cancel("s1");
    }

    @Test
    public void testCancel() {
        dispatcher.register("s1", new LinkedBlockingQueue<SocketChannel>());
        dispatcher.cancel("s1");

        try {
            dispatcher.cancel("s1");
            fail("Expected EnvironmentFailureException");
        } catch(EnvironmentFailureException e) {
        }

        try {
            dispatcher.cancel(null);
            fail("Expected EnvironmentFailureException");
        } catch(EnvironmentFailureException e) {
        }
    }

    @Test
    public void testExceptions()
        throws IOException {

        /* Close connection due to unregistered service name. */
        Socket socket = new Socket();
        socket.connect(dispatcherAddress);
        try {
            ServiceDispatcher.getServiceOutputStream(socket, "s1");
            fail("Expected exception");
        } catch (ServiceConnectFailedException e) {
            assertTrue(true);
        }
    }
}
