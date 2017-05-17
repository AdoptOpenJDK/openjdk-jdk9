/*
 * Copyright (c) 2015, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.incubator.http.internal.websocket;

import jdk.incubator.http.WebSocket;
import jdk.incubator.http.internal.common.Log;
import jdk.incubator.http.internal.common.Pair;
import jdk.incubator.http.internal.websocket.OpeningHandshake.Result;
import jdk.incubator.http.internal.websocket.OutgoingMessage.Binary;
import jdk.incubator.http.internal.websocket.OutgoingMessage.Close;
import jdk.incubator.http.internal.websocket.OutgoingMessage.Context;
import jdk.incubator.http.internal.websocket.OutgoingMessage.Ping;
import jdk.incubator.http.internal.websocket.OutgoingMessage.Pong;
import jdk.incubator.http.internal.websocket.OutgoingMessage.Text;

import java.io.IOException;
import java.net.ProtocolException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static jdk.incubator.http.internal.common.Pair.pair;
import static jdk.incubator.http.internal.websocket.StatusCodes.CLOSED_ABNORMALLY;
import static jdk.incubator.http.internal.websocket.StatusCodes.NO_STATUS_CODE;
import static jdk.incubator.http.internal.websocket.StatusCodes.isLegalToSendFromClient;

/*
 * A WebSocket client.
 */
final class WebSocketImpl implements WebSocket {

    private final URI uri;
    private final String subprotocol;
    private final RawChannel channel;
    private final Listener listener;

    /*
     * Whether or not Listener.onClose or Listener.onError has been already
     * invoked. We keep track of this since only one of these methods is invoked
     * and it is invoked at most once.
     */
    private boolean lastMethodInvoked;
    private final AtomicBoolean outstandingSend = new AtomicBoolean();
    private final CooperativeHandler sendHandler =
              new CooperativeHandler(this::sendFirst);
    private final Queue<Pair<OutgoingMessage, CompletableFuture<WebSocket>>>
            queue = new ConcurrentLinkedQueue<>();
    private final Context context = new OutgoingMessage.Context();
    private final Transmitter transmitter;
    private final Receiver receiver;

    /*
     * Whether or not the WebSocket has been closed. When a WebSocket has been
     * closed it means that no further messages can be sent or received.
     * A closure can be triggered by:
     *
     *   1. abort()
     *   2. "Failing the WebSocket Connection" (i.e. a fatal error)
     *   3. Completion of the Closing handshake
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /*
     * This lock is enforcing sequential ordering of invocations to listener's
     * methods. It is supposed to be uncontended. The only contention that can
     * happen is when onOpen, an asynchronous onError (not related to reading
     * from the channel, e.g. an error from automatic Pong reply) or onClose
     * (related to abort) happens. Since all of the above are one-shot actions,
     * the said contention is insignificant.
     */
    private final Object lock = new Object();

    private final CompletableFuture<?> closeReceived = new CompletableFuture<>();
    private final CompletableFuture<?> closeSent = new CompletableFuture<>();

    static CompletableFuture<WebSocket> newInstanceAsync(BuilderImpl b) {
        Function<Result, WebSocket> newWebSocket = r -> {
            WebSocketImpl ws = new WebSocketImpl(b.getUri(),
                                                 r.subprotocol,
                                                 r.channel,
                                                 b.getListener());
            // The order of calls might cause a subtle effects, like CF will be
            // returned from the buildAsync _after_ onOpen has been signalled.
            // This means if onOpen is lengthy, it might cause some problems.
            ws.signalOpen();
            return ws;
        };
        OpeningHandshake h;
        try {
            h = new OpeningHandshake(b);
        } catch (IllegalArgumentException e) {
            return failedFuture(e);
        }
        return h.send().thenApply(newWebSocket);
    }

    WebSocketImpl(URI uri,
                  String subprotocol,
                  RawChannel channel,
                  Listener listener)
    {
        this.uri = requireNonNull(uri);
        this.subprotocol = requireNonNull(subprotocol);
        this.channel = requireNonNull(channel);
        this.listener = requireNonNull(listener);
        this.transmitter = new Transmitter(channel);
        this.receiver = new Receiver(messageConsumerOf(listener), channel);

        // Set up the Closing Handshake action
        CompletableFuture.allOf(closeReceived, closeSent)
                .whenComplete((result, error) -> {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        Log.logError(e);
                    } finally {
                        closed.set(true);
                    }
                });
    }

    /*
     * This initialisation is outside of the constructor for the sake of
     * safe publication.
     */
    private void signalOpen() {
        synchronized (lock) {
            // TODO: might hold lock longer than needed causing prolonged
            // contention? substitute lock for ConcurrentLinkedQueue<Runnable>?
            try {
                listener.onOpen(this);
            } catch (Exception e) {
                signalError(e);
            }
        }
    }

    private void signalError(Throwable error) {
        synchronized (lock) {
            if (lastMethodInvoked) {
                Log.logError(error);
            } else {
                lastMethodInvoked = true;
                receiver.close();
                try {
                    listener.onError(this, error);
                } catch (Exception e) {
                    Log.logError(e);
                }
            }
        }
    }

    /*
     * Processes a Close event that came from the channel. Invoked at most once.
     */
    private void processClose(int statusCode, String reason) {
        receiver.close();
        try {
            channel.shutdownInput();
        } catch (IOException e) {
            Log.logError(e);
        }
        boolean alreadyCompleted = !closeReceived.complete(null);
        if (alreadyCompleted) {
            // This CF is supposed to be completed only once, the first time a
            // Close message is received. No further messages are pulled from
            // the socket.
            throw new InternalError();
        }
        int code;
        if (statusCode == NO_STATUS_CODE || statusCode == CLOSED_ABNORMALLY) {
            code = NORMAL_CLOSURE;
        } else {
            code = statusCode;
        }
        CompletionStage<?> readyToClose = signalClose(statusCode, reason);
        if (readyToClose == null) {
            readyToClose = CompletableFuture.completedFuture(null);
        }
        readyToClose.whenComplete((r, error) -> {
            enqueueClose(new Close(code, ""))
                    .whenComplete((r1, error1) -> {
                        if (error1 != null) {
                            Log.logError(error1);
                        }
                    });
        });
    }

    /*
     * Signals a Close event (might not correspond to anything happened on the
     * channel, e.g. `abort()`).
     */
    private CompletionStage<?> signalClose(int statusCode, String reason) {
        synchronized (lock) {
            if (lastMethodInvoked) {
                Log.logTrace("Close: {0}, ''{1}''", statusCode, reason);
            } else {
                lastMethodInvoked = true;
                receiver.close();
                try {
                    return listener.onClose(this, statusCode, reason);
                } catch (Exception e) {
                    Log.logError(e);
                }
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<WebSocket> sendText(CharSequence message,
                                                 boolean isLast)
    {
        return enqueueExclusively(new Text(message, isLast));
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer message,
                                                   boolean isLast)
    {
        return enqueueExclusively(new Binary(message, isLast));
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
        return enqueueExclusively(new Ping(message));
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
        return enqueueExclusively(new Pong(message));
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode,
                                                  String reason) {
        if (!isLegalToSendFromClient(statusCode)) {
            return failedFuture(
                    new IllegalArgumentException("statusCode: " + statusCode));
        }
        Close msg;
        try {
            msg = new Close(statusCode, reason);
        } catch (IllegalArgumentException e) {
            return failedFuture(e);
        }
        return enqueueClose(msg);
    }

    /*
     * Sends a Close message with the given contents and then shuts down the
     * channel for writing since no more messages are expected to be sent after
     * this. Invoked at most once.
     */
    private CompletableFuture<WebSocket> enqueueClose(Close m) {
        return enqueue(m).whenComplete((r, error) -> {
            try {
                channel.shutdownOutput();
            } catch (IOException e) {
                Log.logError(e);
            }
            boolean alreadyCompleted = !closeSent.complete(null);
            if (alreadyCompleted) {
                // Shouldn't happen as this callback must run at most once
                throw new InternalError();
            }
        });
    }

    /*
     * Accepts the given message into the outgoing queue in a mutually-exclusive
     * fashion in respect to other messages accepted through this method. No
     * further messages will be accepted until the returned CompletableFuture
     * completes. This method is used to enforce "one outstanding send
     * operation" policy.
     */
    private CompletableFuture<WebSocket> enqueueExclusively(OutgoingMessage m)
    {
        if (closed.get()) {
            return failedFuture(new IllegalStateException("Closed"));
        }
        if (!outstandingSend.compareAndSet(false, true)) {
            return failedFuture(new IllegalStateException("Outstanding send"));
        }
        return enqueue(m).whenComplete((r, e) -> outstandingSend.set(false));
    }

    private CompletableFuture<WebSocket> enqueue(OutgoingMessage m) {
        CompletableFuture<WebSocket> cf = new CompletableFuture<>();
        boolean added = queue.add(pair(m, cf));
        if (!added) {
            // The queue is supposed to be unbounded
            throw new InternalError();
        }
        sendHandler.handle();
        return cf;
    }

    /*
     * This is the main sending method. It may be run in different threads,
     * but never concurrently.
     */
    private void sendFirst(Runnable whenSent) {
        Pair<OutgoingMessage, CompletableFuture<WebSocket>> p = queue.poll();
        if (p == null) {
            whenSent.run();
            return;
        }
        OutgoingMessage message = p.first;
        CompletableFuture<WebSocket> cf = p.second;
        try {
            message.contextualize(context);
            Consumer<Exception> h = e -> {
                if (e == null) {
                    cf.complete(WebSocketImpl.this);
                } else {
                    cf.completeExceptionally(e);
                }
                sendHandler.handle();
                whenSent.run();
            };
            transmitter.send(message, h);
        } catch (Exception t) {
            cf.completeExceptionally(t);
        }
    }

    @Override
    public void request(long n) {
        receiver.request(n);
    }

    @Override
    public String getSubprotocol() {
        return subprotocol;
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void abort() throws IOException {
        try {
            channel.close();
        } finally {
            closed.set(true);
            signalClose(CLOSED_ABNORMALLY, "");
        }
    }

    @Override
    public String toString() {
        return super.toString()
                + "[" + (closed.get() ? "CLOSED" : "OPEN") + "]: " + uri
                + (!subprotocol.isEmpty() ? ", subprotocol=" + subprotocol : "");
    }

    private MessageStreamConsumer messageConsumerOf(Listener listener) {
        // Synchronization performed here in every method is not for the sake of
        // ordering invocations to this consumer, after all they are naturally
        // ordered in the channel. The reason is to avoid an interference with
        // any unrelated to the channel calls to onOpen, onClose and onError.
        return new MessageStreamConsumer() {

            @Override
            public void onText(MessagePart part, CharSequence data) {
                receiver.acknowledge();
                synchronized (WebSocketImpl.this.lock) {
                    try {
                        listener.onText(WebSocketImpl.this, data, part);
                    } catch (Exception e) {
                        signalError(e);
                    }
                }
            }

            @Override
            public void onBinary(MessagePart part, ByteBuffer data) {
                receiver.acknowledge();
                synchronized (WebSocketImpl.this.lock) {
                    try {
                        listener.onBinary(WebSocketImpl.this, data.slice(), part);
                    } catch (Exception e) {
                        signalError(e);
                    }
                }
            }

            @Override
            public void onPing(ByteBuffer data) {
                receiver.acknowledge();
                // Let's make a full copy of this tiny data. What we want here
                // is to rule out a possibility the shared data we send might be
                // corrupted the by processing in the listener.
                ByteBuffer slice = data.slice();
                ByteBuffer copy = ByteBuffer.allocate(data.remaining())
                        .put(data)
                        .flip();
                // Non-exclusive send;
                CompletableFuture<WebSocket> pongSent = enqueue(new Pong(copy));
                pongSent.whenComplete(
                        (r, error) -> {
                            if (error != null) {
                                WebSocketImpl.this.signalError(error);
                            }
                        }
                );
                synchronized (WebSocketImpl.this.lock) {
                    try {
                        listener.onPing(WebSocketImpl.this, slice);
                    } catch (Exception e) {
                        signalError(e);
                    }
                }
            }

            @Override
            public void onPong(ByteBuffer data) {
                receiver.acknowledge();
                synchronized (WebSocketImpl.this.lock) {
                    try {
                        listener.onPong(WebSocketImpl.this, data.slice());
                    } catch (Exception e) {
                        signalError(e);
                    }
                }
            }

            @Override
            public void onClose(int statusCode, CharSequence reason) {
                receiver.acknowledge();
                processClose(statusCode, reason.toString());
            }

            @Override
            public void onError(Exception error) {
                // An signalError doesn't necessarily mean we must signalClose
                // the WebSocket. However, if it's something the WebSocket
                // Specification recognizes as a reason for "Failing the
                // WebSocket Connection", then we must do so, but BEFORE
                // notifying the Listener.
                if (!(error instanceof FailWebSocketException)) {
                    signalError(error);
                } else {
                    Exception ex = (Exception) new ProtocolException().initCause(error);
                    int code = ((FailWebSocketException) error).getStatusCode();
                    enqueueClose(new Close(code, ""))
                            .whenComplete((r, e) -> {
                                if (e != null) {
                                    ex.addSuppressed(e);
                                }
                                try {
                                    channel.close();
                                } catch (IOException e1) {
                                    ex.addSuppressed(e1);
                                } finally {
                                    closed.set(true);
                                }
                                signalError(ex);
                            });
                }
            }

            @Override
            public void onComplete() {
                processClose(CLOSED_ABNORMALLY, "");
            }
        };
    }
}
