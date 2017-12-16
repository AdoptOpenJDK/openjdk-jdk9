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

package jdk.incubator.http;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.SocketPermission;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLPermission;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import jdk.incubator.http.internal.common.MinimalFuture;
import jdk.incubator.http.internal.common.Utils;
import jdk.incubator.http.internal.common.Log;

/**
 * One request/response exchange (handles 100/101 intermediate response also).
 * depth field used to track number of times a new request is being sent
 * for a given API request. If limit exceeded exception is thrown.
 *
 * Security check is performed here:
 * - uses AccessControlContext captured at API level
 * - checks for appropriate URLPermission for request
 * - if permission allowed, grants equivalent SocketPermission to call
 * - in case of direct HTTP proxy, checks additionally for access to proxy
 *    (CONNECT proxying uses its own Exchange, so check done there)
 *
 */
final class Exchange<T> {

    final HttpRequestImpl request;
    final HttpClientImpl client;
    volatile ExchangeImpl<T> exchImpl;
    // used to record possible cancellation raised before the exchImpl
    // has been established.
    private volatile IOException failed;
    final List<SocketPermission> permissions = new LinkedList<>();
    final AccessControlContext acc;
    final MultiExchange<?,T> multi;
    final Executor parentExecutor;
    final HttpRequest.BodyProcessor requestProcessor;
    boolean upgrading; // to HTTP/2
    final PushGroup<?,T> pushGroup;

    Exchange(HttpRequestImpl request, MultiExchange<?,T> multi) {
        this.request = request;
        this.upgrading = false;
        this.client = multi.client();
        this.multi = multi;
        this.acc = multi.acc;
        this.parentExecutor = multi.executor;
        this.requestProcessor = request.requestProcessor;
        this.pushGroup = multi.pushGroup;
    }

    /* If different AccessControlContext to be used  */
    Exchange(HttpRequestImpl request,
             MultiExchange<?,T> multi,
             AccessControlContext acc)
    {
        this.request = request;
        this.acc = acc;
        this.upgrading = false;
        this.client = multi.client();
        this.multi = multi;
        this.parentExecutor = multi.executor;
        this.requestProcessor = request.requestProcessor;
        this.pushGroup = multi.pushGroup;
    }

    PushGroup<?,T> getPushGroup() {
        return pushGroup;
    }

    Executor executor() {
        return parentExecutor;
    }

    public HttpRequestImpl request() {
        return request;
    }

    HttpClientImpl client() {
        return client;
    }

    public Response response() throws IOException, InterruptedException {
        return responseImpl(null);
    }

    public T readBody(HttpResponse.BodyHandler<T> responseHandler) throws IOException {
        // The connection will not be returned to the pool in the case of WebSocket
        return exchImpl.readBody(responseHandler, !request.isWebSocket());
    }

    public CompletableFuture<T> readBodyAsync(HttpResponse.BodyHandler<T> handler) {
        // The connection will not be returned to the pool in the case of WebSocket
        return exchImpl.readBodyAsync(handler, !request.isWebSocket(), parentExecutor);
    }

    public void cancel() {
        // cancel can be called concurrently before or at the same time
        // that the exchange impl is being established.
        // In that case we won't be able to propagate the cancellation
        // right away
        if (exchImpl != null) {
            exchImpl.cancel();
        } else {
            // no impl - can't cancel impl yet.
            // call cancel(IOException) instead which takes care
            // of race conditions between impl/cancel.
            cancel(new IOException("Request cancelled"));
        }
    }

    public void cancel(IOException cause) {
        // If the impl is non null, propagate the exception right away.
        // Otherwise record it so that it can be propagated once the
        // exchange impl has been established.
        ExchangeImpl<?> impl = exchImpl;
        if (impl != null) {
            // propagate the exception to the impl
            impl.cancel(cause);
        } else {
            try {
                // no impl yet. record the exception
                failed = cause;
                // now call checkCancelled to recheck the impl.
                // if the failed state is set and the impl is not null, reset
                // the failed state and propagate the exception to the impl.
                checkCancelled(false);
            } catch (IOException x) {
                // should not happen - we passed 'false' above
                throw new UncheckedIOException(x);
            }
        }
    }

    // This method will raise an exception if one was reported and if
    // it is possible to do so. If the exception can be raised, then
    // the failed state will be reset. Otherwise, the failed state
    // will persist until the exception can be raised and the failed state
    // can be cleared.
    // Takes care of possible race conditions.
    private void checkCancelled(boolean throwIfNoImpl) throws IOException {
        ExchangeImpl<?> impl = null;
        IOException cause = null;
        if (failed != null) {
            synchronized(this) {
                cause = failed;
                impl = exchImpl;
                if (throwIfNoImpl || impl != null) {
                    // The exception will be raised by one of the two methods
                    // below: reset the failed state.
                    failed = null;
                }
            }
        }
        if (cause == null) return;
        if (impl != null) {
            // The exception is raised by propagating it to the impl.
            impl.cancel(cause);
        } else if (throwIfNoImpl) {
            // The exception is raised by throwing it immediately
            throw cause;
        } else {
            Log.logTrace("Exchange: request [{0}/timeout={1}ms] no impl is set."
                         + "\n\tCan''t cancel yet with {2}",
                         request.uri(),
                         request.duration() == null ? -1 :
                         // calling duration.toMillis() can throw an exception.
                         // this is just debugging, we don't care if it overflows.
                         (request.duration().getSeconds() * 1000
                          + request.duration().getNano() / 1000000),
                         cause);
        }
    }

    public void h2Upgrade() {
        upgrading = true;
        request.setH2Upgrade(client.client2());
    }

    static final SocketPermission[] SOCKET_ARRAY = new SocketPermission[0];

    Response responseImpl(HttpConnection connection)
        throws IOException, InterruptedException
    {
        SecurityException e = securityCheck(acc);
        if (e != null) {
            throw e;
        }

        if (permissions.size() > 0) {
            try {
                return AccessController.doPrivileged(
                        (PrivilegedExceptionAction<Response>)() ->
                             responseImpl0(connection),
                        null,
                        permissions.toArray(SOCKET_ARRAY));
            } catch (Throwable ee) {
                if (ee instanceof PrivilegedActionException) {
                    ee = ee.getCause();
                }
                if (ee instanceof IOException) {
                    throw (IOException) ee;
                } else {
                    throw new RuntimeException(ee); // TODO: fix
                }
            }
        } else {
            return responseImpl0(connection);
        }
    }

    // get/set the exchange impl, solving race condition issues with
    // potential concurrent calls to cancel() or cancel(IOException)
    private void establishExchange(HttpConnection connection)
        throws IOException, InterruptedException
    {
        // check if we have been cancelled first.
        checkCancelled(true);
        // not yet cancelled: create/get a new impl
        exchImpl = ExchangeImpl.get(this, connection);
        // recheck for cancelled, in case of race conditions
        checkCancelled(true);
        // now we're good to go. because exchImpl is no longer null
        // cancel() will be able to propagate directly to the impl
        // after this point.
    }

    private Response responseImpl0(HttpConnection connection)
        throws IOException, InterruptedException
    {
        establishExchange(connection);
        exchImpl.setClientForRequest(requestProcessor);
        if (request.expectContinue()) {
            Log.logTrace("Sending Expect: 100-Continue");
            request.addSystemHeader("Expect", "100-Continue");
            exchImpl.sendHeadersOnly();

            Log.logTrace("Waiting for 407-Expectation-Failed or 100-Continue");
            Response resp = exchImpl.getResponse();
            HttpResponseImpl.logResponse(resp);
            int rcode = resp.statusCode();
            if (rcode != 100) {
                Log.logTrace("Expectation failed: Received {0}",
                             rcode);
                if (upgrading && rcode == 101) {
                    throw new IOException(
                        "Unable to handle 101 while waiting for 100-Continue");
                }
                return resp;
            }

            Log.logTrace("Received 100-Continue: sending body");
            exchImpl.sendBody();

            Log.logTrace("Body sent: waiting for response");
            resp = exchImpl.getResponse();
            HttpResponseImpl.logResponse(resp);

            return checkForUpgrade(resp, exchImpl);
        } else {
            exchImpl.sendHeadersOnly();
            exchImpl.sendBody();
            Response resp = exchImpl.getResponse();
            HttpResponseImpl.logResponse(resp);
            return checkForUpgrade(resp, exchImpl);
        }
    }

    // Completed HttpResponse will be null if response succeeded
    // will be a non null responseAsync if expect continue returns an error

    public CompletableFuture<Response> responseAsync() {
        return responseAsyncImpl(null);
    }

    CompletableFuture<Response> responseAsyncImpl(HttpConnection connection) {
        SecurityException e = securityCheck(acc);
        if (e != null) {
            return MinimalFuture.failedFuture(e);
        }
        if (permissions.size() > 0) {
            return AccessController.doPrivileged(
                    (PrivilegedAction<CompletableFuture<Response>>)() ->
                        responseAsyncImpl0(connection),
                    null,
                    permissions.toArray(SOCKET_ARRAY));
        } else {
            return responseAsyncImpl0(connection);
        }
    }

    CompletableFuture<Response> responseAsyncImpl0(HttpConnection connection) {
        try {
            establishExchange(connection);
        } catch (IOException | InterruptedException e) {
            return MinimalFuture.failedFuture(e);
        }
        if (request.expectContinue()) {
            request.addSystemHeader("Expect", "100-Continue");
            Log.logTrace("Sending Expect: 100-Continue");
            return exchImpl
                    .sendHeadersAsync()
                    .thenCompose(v -> exchImpl.getResponseAsync(parentExecutor))
                    .thenCompose((Response r1) -> {
                        HttpResponseImpl.logResponse(r1);
                        int rcode = r1.statusCode();
                        if (rcode == 100) {
                            Log.logTrace("Received 100-Continue: sending body");
                            CompletableFuture<Response> cf =
                                    exchImpl.sendBodyAsync()
                                            .thenCompose(exIm -> exIm.getResponseAsync(parentExecutor));
                            cf = wrapForUpgrade(cf);
                            cf = wrapForLog(cf);
                            return cf;
                        } else {
                            Log.logTrace("Expectation failed: Received {0}",
                                         rcode);
                            if (upgrading && rcode == 101) {
                                IOException failed = new IOException(
                                        "Unable to handle 101 while waiting for 100");
                                return MinimalFuture.failedFuture(failed);
                            }
                            return exchImpl.readBodyAsync(this::ignoreBody, false, parentExecutor)
                                  .thenApply(v ->  r1);
                        }
                    });
        } else {
            CompletableFuture<Response> cf = exchImpl
                    .sendHeadersAsync()
                    .thenCompose(ExchangeImpl::sendBodyAsync)
                    .thenCompose(exIm -> exIm.getResponseAsync(parentExecutor));
            cf = wrapForUpgrade(cf);
            cf = wrapForLog(cf);
            return cf;
        }
    }

    private CompletableFuture<Response> wrapForUpgrade(CompletableFuture<Response> cf) {
        if (upgrading) {
            return cf.thenCompose(r -> checkForUpgradeAsync(r, exchImpl));
        }
        return cf;
    }

    private CompletableFuture<Response> wrapForLog(CompletableFuture<Response> cf) {
        if (Log.requests()) {
            return cf.thenApply(response -> {
                HttpResponseImpl.logResponse(response);
                return response;
            });
        }
        return cf;
    }

    HttpResponse.BodyProcessor<T> ignoreBody(int status, HttpHeaders hdrs) {
        return HttpResponse.BodyProcessor.discard((T)null);
    }

    // if this response was received in reply to an upgrade
    // then create the Http2Connection from the HttpConnection
    // initialize it and wait for the real response on a newly created Stream

    private CompletableFuture<Response>
    checkForUpgradeAsync(Response resp,
                         ExchangeImpl<T> ex) {

        int rcode = resp.statusCode();
        if (upgrading && (rcode == 101)) {
            Http1Exchange<T> e = (Http1Exchange<T>)ex;
            // check for 101 switching protocols
            // 101 responses are not supposed to contain a body.
            //    => should we fail if there is one?
            return e.readBodyAsync(this::ignoreBody, false, parentExecutor)
                .thenCompose((T v) -> // v is null
                     Http2Connection.createAsync(e.connection(),
                                                 client.client2(),
                                                 this, e.getBuffer())
                        .thenCompose((Http2Connection c) -> {
                            c.putConnection();
                            Stream<T> s = c.getStream(1);
                            exchImpl = s;
                            return s.getResponseAsync(null);
                        })
                );
        }
        return MinimalFuture.completedFuture(resp);
    }

    private Response checkForUpgrade(Response resp,
                                             ExchangeImpl<T> ex)
        throws IOException, InterruptedException
    {
        int rcode = resp.statusCode();
        if (upgrading && (rcode == 101)) {
            Http1Exchange<T> e = (Http1Exchange<T>) ex;

            // 101 responses are not supposed to contain a body.
            //    => should we fail if there is one?
            //    => readBody called here by analogy with
            //       checkForUpgradeAsync above
            e.readBody(this::ignoreBody, false);

            // must get connection from Http1Exchange
            Http2Connection h2con = new Http2Connection(e.connection(),
                                                        client.client2(),
                                                        this, e.getBuffer());
            h2con.putConnection();
            Stream<T> s = h2con.getStream(1);
            exchImpl = s;
            Response xx = s.getResponse();
            HttpResponseImpl.logResponse(xx);
            return xx;
        }
        return resp;
    }

    private URI getURIForSecurityCheck() {
        URI u;
        String method = request.method();
        InetSocketAddress authority = request.authority();
        URI uri = request.uri();

        // CONNECT should be restricted at API level
        if (method.equalsIgnoreCase("CONNECT")) {
            try {
                u = new URI("socket",
                             null,
                             authority.getHostString(),
                             authority.getPort(),
                             null,
                             null,
                             null);
            } catch (URISyntaxException e) {
                throw new InternalError(e); // shouldn't happen
            }
        } else {
            u = uri;
        }
        return u;
    }

    /**
     * Do the security check and return any exception.
     * Return null if no check needed or passes.
     *
     * Also adds any generated permissions to the "permissions" list.
     */
    private SecurityException securityCheck(AccessControlContext acc) {
        SecurityManager sm = System.getSecurityManager();
        if (sm == null) {
            return null;
        }

        String method = request.method();
        HttpHeaders userHeaders = request.getUserHeaders();
        URI u = getURIForSecurityCheck();
        URLPermission p = Utils.getPermission(u, method, userHeaders.map());

        try {
            assert acc != null;
            sm.checkPermission(p, acc);
            permissions.add(getSocketPermissionFor(u));
        } catch (SecurityException e) {
            return e;
        }
        ProxySelector ps = client.proxy().orElse(null);
        if (ps != null) {
            InetSocketAddress proxy = (InetSocketAddress)
                    ps.select(u).get(0).address(); // TODO: check this
            // may need additional check
            if (!method.equals("CONNECT")) {
                // a direct http proxy. Need to check access to proxy
                try {
                    u = new URI("socket", null, proxy.getHostString(),
                        proxy.getPort(), null, null, null);
                } catch (URISyntaxException e) {
                    throw new InternalError(e); // shouldn't happen
                }
                p = new URLPermission(u.toString(), "CONNECT");
                try {
                    sm.checkPermission(p, acc);
                } catch (SecurityException e) {
                    permissions.clear();
                    return e;
                }
                String sockperm = proxy.getHostString() +
                        ":" + Integer.toString(proxy.getPort());

                permissions.add(new SocketPermission(sockperm, "connect,resolve"));
            }
        }
        return null;
    }

    HttpClient.Redirect followRedirects() {
        return client.followRedirects();
    }

    HttpClient.Version version() {
        return multi.version();
    }

    private static SocketPermission getSocketPermissionFor(URI url) {
        if (System.getSecurityManager() == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        String host = url.getHost();
        sb.append(host);
        int port = url.getPort();
        if (port == -1) {
            String scheme = url.getScheme();
            if ("http".equals(scheme)) {
                sb.append(":80");
            } else { // scheme must be https
                sb.append(":443");
            }
        } else {
            sb.append(':')
              .append(Integer.toString(port));
        }
        String target = sb.toString();
        return new SocketPermission(target, "connect");
    }

    AccessControlContext getAccessControlContext() {
        return acc;
    }
}
