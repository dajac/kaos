package io.github.dajac.kaos.server;

import io.github.dajac.kaos.server.dto.MetaResponse;
import io.javalin.Javalin;

/**
 * The HTTP server. It owns the Javalin instance and registers the routes of the HTTP API v1.
 *
 * <p>Usage: construct, {@link #start(int)}, then {@link #close()}.
 */
public final class Server implements AutoCloseable {

    static final String API_V1 = "/api/v1";

    private final Javalin app;

    public Server() {
        MetaResponse meta = new MetaResponse(Version.read());
        this.app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            // The watcher is a non-daemon thread that lives for five seconds after creation. The
            // server is always started right after it is created, and a failed start must not
            // leave a thread that delays the JVM exit.
            config.startup.startupWatcherEnabled = false;
            config.concurrency.useVirtualThreads = true;
            config.routes.get(API_V1 + "/meta", ctx -> ctx.json(meta));
        });
    }

    /**
     * Starts the server and blocks until it accepts connections.
     *
     * <p>A failed start stops whatever was already started, so the server holds no port and no
     * non-daemon thread afterwards and does not keep the JVM alive.
     *
     * @param port the port to listen on; 0 picks a free port
     * @return this server
     * @throws IllegalStateException if the server cannot start, for example because the port is
     *     already in use
     */
    public Server start(int port) {
        try {
            app.start(port);
        } catch (RuntimeException e) {
            try {
                app.stop();
            } catch (RuntimeException stopFailure) {
                e.addSuppressed(stopFailure);
            }
            throw new IllegalStateException("Cannot start the HTTP server on port " + port + ": " + e.getMessage(), e);
        }
        return this;
    }

    /**
     * Returns the port the server listens on. Only valid after {@link #start(int)}.
     */
    public int port() {
        return app.port();
    }

    /**
     * Stops the server. Calling it more than once has no further effect.
     */
    @Override
    public void close() {
        app.stop();
    }
}
