/*
 * Copyright 2026 The Kaos Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dajac.kaos.server;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.dajac.kaos.Simulator;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;

/**
 * The HTTP server. It owns the Javalin instance, the {@link SimulationService} with its
 * {@link Simulator}, and the {@link SseHub}, registers the routes of the HTTP API v1 and serves the
 * UI through {@link StaticUi}.
 *
 * <p>Usage: construct, {@link #start(int)}, then {@link #close()}, which also closes the simulator.
 */
public final class Server implements AutoCloseable {

    private final Javalin app;
    private final SimulationService service;
    private final SseHub hub;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a server for the given simulator, which it owns from now on.
     */
    public Server(Simulator simulator) {
        this(simulator, SseHub.PING_INTERVAL, new StaticUi());
    }

    Server(Simulator simulator, Duration pingInterval) {
        this(simulator, pingInterval, new StaticUi());
    }

    Server(Simulator simulator, Duration pingInterval, StaticUi ui) {
        var mapper = Json.mapper();
        this.service = new SimulationService(simulator);
        this.hub = new SseHub(mapper, service::snapshot, service::status, pingInterval);
        service.onChange(hub::changed);
        var routes = new ApiRoutes(mapper, service, hub, Version.read());
        var problems = new ProblemHandler(mapper);
        this.app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            // The watcher is a non-daemon thread that lives for five seconds after creation. The
            // server is always started right after it is created, and a failed start must not
            // leave a thread that delays the JVM exit.
            config.startup.startupWatcherEnabled = false;
            config.concurrency.useVirtualThreads = true;
            config.jsonMapper(new JavalinJackson(mapper, true));
            routes.register(config.routes);
            problems.register(config.routes);
            ui.register(config);
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
     * Ends the event streams, stops the HTTP server, then closes the simulator, which removes
     * everything it created on the cluster. Calling it more than once has no further effect.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            hub.close();
            app.stop();
        } finally {
            service.close();
        }
    }
}
