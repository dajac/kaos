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
            config.concurrency.useVirtualThreads = true;
            config.routes.get(API_V1 + "/meta", ctx -> ctx.json(meta));
        });
    }

    /**
     * Starts the server and blocks until it accepts connections.
     *
     * @param port the port to listen on; 0 picks a free port
     * @return this server
     */
    public Server start(int port) {
        app.start(port);
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
