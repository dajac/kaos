package io.github.dajac.kaos.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import io.github.dajac.kaos.Simulator;
import io.github.dajac.kaos.server.ApiClient.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The event stream, against a simulator whose start fails at once. */
class SseHubTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PING_INTERVAL = Duration.ofMillis(200);

    private Server server;
    private ApiClient api;

    @BeforeEach
    void startServer() {
        server = new Server(new Simulator("kaos-test.invalid:9092"), PING_INTERVAL).start(0);
        api = new ApiClient(server);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void sendsTheStatusAndTheSnapshotOnConnect() throws Exception {
        var events = api.events();

        var status = next(events);
        assertThat(status.name()).isEqualTo("status");
        assertThat(status.json().get("phase").asText()).isEqualTo("IDLE");
        assertThat(status.json().get("lastError").asText()).isEmpty();

        var snapshot = next(events);
        assertThat(snapshot.name()).isEqualTo("snapshot");
        assertThat(snapshot.id()).isEqualTo("0");
        assertThat(snapshot.json().get("revision").asLong()).isZero();
        assertThat(snapshot.json().get("phase").asText()).isEqualTo("IDLE");
    }

    @Test
    void sendsANewSnapshotAndStatusOnChange() throws Exception {
        var events = api.events();
        next(events);
        next(events);

        assertThat(api.post("/api/v1/simulator", """
            {"topics": [{"name": "orders"}]}
            """).statusCode()).isEqualTo(202);

        // The stream is coalesced, so the start may show up as STARTING or directly as its failure.
        var received = awaitEvents(
            events,
            e -> "status".equals(e.name()) && !e.json().get("lastError").asText().isEmpty()
        );
        var snapshots = received.stream().filter(e -> "snapshot".equals(e.name())).toList();
        assertThat(snapshots).isNotEmpty().allSatisfy(snapshot -> {
            assertThat(Long.parseLong(snapshot.id())).isPositive();
            assertThat(snapshot.json().get("revision").asLong()).isEqualTo(Long.parseLong(snapshot.id()));
        });
        assertThat(received.getLast().json().get("phase").asText()).isEqualTo("IDLE");
    }

    @Test
    void pingsAQuietStream() throws Exception {
        var events = api.events();
        next(events);
        next(events);

        var ping = next(events);
        assertThat(ping.name()).isEqualTo("comment");
        assertThat(ping.data()).isEqualTo("ping");
    }

    @Test
    void closingTheServerEndsTheStreams() throws Exception {
        var events = api.events();
        next(events);
        next(events);

        server.close();

        // Only pings could still be in flight; after them the stream ends and nothing more comes.
        var after = new ArrayList<Event>();
        events.drainTo(after);
        assertThat(after).allSatisfy(e -> assertThat(e.name()).isEqualTo("comment"));
        assertThat(events.poll(PING_INTERVAL.toMillis() * 3, TimeUnit.MILLISECONDS)).isNull();
    }

    private static Event next(BlockingQueue<Event> events) throws InterruptedException {
        var event = events.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(event).as("an event within " + TIMEOUT).isNotNull();
        return event;
    }

    /**
     * Returns the events received up to the first one that matches, which is the last one. After
     * that event, the snapshot of the same change may still be in flight, so the method also waits
     * for a quiet stream, a ping.
     */
    private static List<Event> awaitEvents(BlockingQueue<Event> events, Predicate<Event> condition)
        throws InterruptedException {
        var seen = new ArrayList<Event>();
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        Event match = null;
        while (System.nanoTime() < deadline) {
            var event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (event == null) {
                break;
            }
            if (match != null && "comment".equals(event.name())) {
                seen.add(match);
                return seen;
            }
            if (match == null && condition.test(event)) {
                match = event;
            } else if (!"comment".equals(event.name())) {
                seen.add(event);
            }
        }
        throw new AssertionError("No matching event followed by a ping; received " + seen + ", match " + match);
    }
}
