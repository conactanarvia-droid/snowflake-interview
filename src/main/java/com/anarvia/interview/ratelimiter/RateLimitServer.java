package com.anarvia.interview.ratelimiter;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP front end on the JDK's built-in {@link HttpServer}: no framework.
 *
 * <p>Binds to loopback only, because the admin endpoints are unauthenticated.
 * Exposing this beyond localhost needs auth on {@code /v1/admin} first.
 *
 * <p>One virtual thread per request: a request that waits on a busy
 * bucket's lock parks cheaply instead of tying up a pool thread.
 */
public final class RateLimitServer implements AutoCloseable {

    /**
     * Pending-connection queue. The OS default (often 50) resets connections
     * during a traffic burst, exactly when a rate limiter must answer, with a 429, not a reset.
     */
    static final int ACCEPT_BACKLOG = 1024;

    private final HttpServer server;
    private final ExecutorService executor;

    /** {@code port} 0 picks a free port; see {@link #port()}. */
    public RateLimitServer(int port, RateLimiterService service) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), ACCEPT_BACKLOG);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.createContext(CheckHandler.PATH, new CheckHandler(service));
        server.createContext(AdminHandler.PREFIX, new AdminHandler(service));
        server.createContext("/", new HttpUtil.JsonHandler() {
            @Override
            void doHandle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
                HttpUtil.sendError(exchange, 404, "not found");
            }
        });
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdown();
    }
}
