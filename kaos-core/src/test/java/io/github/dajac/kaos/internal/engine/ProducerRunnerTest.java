package io.github.dajac.kaos.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import io.github.dajac.kaos.ProducerSnapshot;
import io.github.dajac.kaos.ProducerSpec;
import io.github.dajac.kaos.ProducerState;
import io.github.dajac.kaos.TopicSpec;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Drives the producer loop against a mock Kafka producer. */
class ProducerRunnerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration QUIET = Duration.ofMillis(500);

    private final MockProducer<String, String> client = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
    private final AtomicInteger changes = new AtomicInteger();
    private final Threads threads = new Threads();
    private final Resources resources = new Resources("test");
    private final ProducerRunner runner = new ProducerRunner(
        new ProducerSpec("load", List.of("a", "b"), 200),
        List.of(new TopicSpec("a", 2), new TopicSpec("b", 1)),
        changes::incrementAndGet
    );

    @AfterEach
    void close() {
        resources.close();
    }

    @Test
    void isStoppedUntilItStarts() {
        assertThat(runner.state()).isEqualTo(ProducerState.STOPPED);
        assertThat(runner.snapshot()).isEqualTo(new ProducerSnapshot("load", List.of("a", "b"), ProducerState.STOPPED, 200));
        assertThat(runner.id()).isEqualTo("load");
        assertThat(runner.topics()).containsExactly("a", "b");
        assertThat(runner.ratePerPartition()).isEqualTo(200);
    }

    @Test
    void writesOneRecordToEveryPartitionAtEachRound() {
        runner.start(() -> client, threads, resources);

        assertThat(runner.state()).isEqualTo(ProducerState.RUNNING);
        assertThat(loopThreads()).containsExactly("kaos-producer-load");
        await().atMost(TIMEOUT).until(() -> client.history().size() >= 6);
        assertThat(client.history().subList(0, 6))
            .extracting(ProducerRecord::topic, ProducerRecord::partition, ProducerRecord::key, ProducerRecord::value)
            .containsExactly(
                tuple("a", 0, "key-0", "value-0"),
                tuple("a", 1, "key-1", "value-0"),
                tuple("b", 0, "key-0", "value-0"),
                tuple("a", 0, "key-0", "value-1"),
                tuple("a", 1, "key-1", "value-1"),
                tuple("b", 0, "key-0", "value-1")
            );
    }

    @Test
    void pauseStopsTheWritesAndResumeRestartsThem() {
        runner.start(() -> client, threads, resources);
        await().atMost(TIMEOUT).until(() -> !client.history().isEmpty());

        runner.pause();
        assertThat(runner.state()).isEqualTo(ProducerState.PAUSED);
        var written = awaitNoMoreWrites();

        runner.resume();
        assertThat(runner.state()).isEqualTo(ProducerState.RUNNING);
        await().atMost(TIMEOUT).until(() -> client.history().size() > written);
    }

    @Test
    void aProducerPausedBeforeItStartsWritesNothing() {
        runner.pause();
        runner.start(() -> client, threads, resources);

        assertThat(runner.state()).isEqualTo(ProducerState.PAUSED);
        assertThat(awaitNoMoreWrites()).isZero();
    }

    @Test
    void reportsEveryStateChangeOnce() {
        runner.start(() -> client, threads, resources);
        assertThat(changes).hasValue(1);

        runner.pause();
        runner.pause();
        assertThat(changes).hasValue(2);

        runner.resume();
        runner.resume();
        assertThat(changes).hasValue(3);

        resources.close();
        assertThat(changes).hasValue(4);
    }

    @Test
    void closingTheResourcesStopsTheThreadAndClosesTheClient() {
        runner.start(() -> client, threads, resources);
        runner.pause();

        resources.close();

        assertThat(runner.state()).isEqualTo(ProducerState.STOPPED);
        assertThat(loopThreads()).isEmpty();
        assertThat(client.closed()).isTrue();
    }

    @Test
    void aFailedRoundDoesNotEndTheLoop() {
        var failOnce = new AtomicInteger();
        var flaky = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer()) {
            @Override
            public synchronized Future<RecordMetadata> send(
                ProducerRecord<String, String> record,
                Callback callback
            ) {
                if (failOnce.getAndIncrement() == 0) {
                    throw new TimeoutException("no metadata");
                }
                return super.send(record, callback);
            }
        };

        runner.start(() -> flaky, threads, resources);

        await().atMost(TIMEOUT).until(() -> !flaky.history().isEmpty());
        // The round that failed is not retried: the next round starts at the first partition.
        assertThat(flaky.history().get(0).value()).isEqualTo("value-1");
        assertThat(flaky.history().get(0).partition()).isZero();
    }

    @Test
    void anUnexpectedErrorEndsTheLoopAndShowsTheProducerAsStopped() {
        var broken = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer()) {
            @Override
            public synchronized Future<RecordMetadata> send(ProducerRecord<String, String> record, Callback callback) {
                throw new UnsupportedOperationException("unexpected");
            }
        };

        runner.start(() -> broken, threads, resources);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(runner.state()).isEqualTo(ProducerState.STOPPED);
            assertThat(loopThreads()).isEmpty();
        });
        // The start and the end of the loop are both reported.
        assertThat(changes).hasValue(2);

        resources.close();
        assertThat(broken.closed()).isTrue();
    }

    /** Waits until nothing is written for a while, and returns the number of records written. */
    private int awaitNoMoreWrites() {
        var last = new AtomicInteger(-1);
        await().atMost(TIMEOUT).pollInterval(QUIET).until(() -> last.getAndSet(client.history().size()) == client.history().size());
        return client.history().size();
    }

    private static List<String> loopThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isAlive)
            .map(Thread::getName)
            .filter(name -> name.startsWith("kaos-producer-"))
            .toList();
    }
}
