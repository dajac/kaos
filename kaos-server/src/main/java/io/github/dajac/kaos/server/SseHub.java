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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dajac.kaos.SimulatorSnapshot;
import io.github.dajac.kaos.server.dto.StatusResponse;
import io.javalin.http.Context;

/**
 * Streams the simulation to the clients of {@code GET /api/v1/simulator/events} as server-sent
 * events:
 *
 * <ul>
 *   <li>{@code snapshot}, with {@code id} set to the revision and the full snapshot as data;</li>
 *   <li>{@code status}, with the {@link StatusResponse} as data, whenever it changes;</li>
 *   <li>a {@code : ping} comment after a quiet {@code pingInterval}.</li>
 * </ul>
 *
 * <p>Each client is served by its own request thread, a virtual thread, which sends the current
 * status and snapshot on connect and then waits for a change. The hub only counts the changes, and a
 * client reads the latest snapshot and status when it wakes up. A slow client thus skips the
 * snapshots published while it was writing and gets the latest one: the stream is coalesced per
 * client. A client that has gone away is noticed at the next write, a ping at the latest.
 */
final class SseHub implements AutoCloseable {

    static final Duration PING_INTERVAL = Duration.ofSeconds(15);

    private final ObjectMapper mapper;
    private final Supplier<SimulatorSnapshot> snapshots;
    private final Supplier<StatusResponse> status;
    private final Duration pingInterval;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private long changes;
    private boolean closed;

    SseHub(
        ObjectMapper mapper,
        Supplier<SimulatorSnapshot> snapshots,
        Supplier<StatusResponse> status,
        Duration pingInterval
    ) {
        this.mapper = mapper;
        this.snapshots = snapshots;
        this.status = status;
        this.pingInterval = pingInterval;
    }

    /** Wakes up the clients, which send what changed. Cheap and non-blocking. */
    void changed() {
        lock.lock();
        try {
            changes++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Ends every stream. */
    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Serves one client until it goes away or the hub closes. Blocks the calling request thread.
     */
    void serve(Context ctx) throws InterruptedException {
        var response = ctx.res();
        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        try {
            var out = response.getOutputStream();
            StatusResponse sentStatus = null;
            SimulatorSnapshot sentSnapshot = null;
            var seen = changes();
            while (seen >= 0) {
                var currentStatus = status.get();
                if (!currentStatus.equals(sentStatus)) {
                    write(out, "event: status\ndata: " + mapper.writeValueAsString(currentStatus) + "\n\n");
                    sentStatus = currentStatus;
                }
                var snapshot = snapshots.get();
                if (snapshot != sentSnapshot) {
                    write(
                        out,
                        "event: snapshot\nid: " + snapshot.revision() + "\ndata: " + mapper.writeValueAsString(snapshot)
                            + "\n\n"
                    );
                    sentSnapshot = snapshot;
                }
                out.flush();
                var next = awaitChange(seen);
                if (next == seen) {
                    write(out, ": ping\n\n");
                    out.flush();
                }
                seen = next;
            }
        } catch (IOException e) {
            // The client has gone away.
        }
    }

    private long changes() {
        lock.lock();
        try {
            return closed ? -1 : changes;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until there are more changes than {@code seen}, or the ping interval has passed.
     *
     * @return the new count of changes, {@code seen} after a quiet interval, or -1 once the hub is
     *     closed
     */
    private long awaitChange(long seen) throws InterruptedException {
        var remaining = pingInterval.toNanos();
        lock.lock();
        try {
            while (!closed && changes == seen && remaining > 0) {
                remaining = changed.awaitNanos(remaining);
            }
            return closed ? -1 : changes;
        } finally {
            lock.unlock();
        }
    }

    private static void write(OutputStream out, String frame) throws IOException {
        out.write(frame.getBytes(StandardCharsets.UTF_8));
    }
}
