package io.github.dajac.kaos.internal.engine;

import io.github.dajac.kaos.ProducerHandle;
import io.github.dajac.kaos.ProducerSnapshot;
import io.github.dajac.kaos.ProducerSpec;
import io.github.dajac.kaos.ProducerState;
import io.github.dajac.kaos.SimulationException;
import io.github.dajac.kaos.TopicSpec;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A load generator: one thread, {@code kaos-producer-<id>}, that owns one Kafka producer. At each
 * tick of its rate it writes one record to every partition of its topics.
 *
 * <p>{@link #pause()} and {@link #resume()} only flip a flag that the loop reads. The loop waits
 * with {@code park}, so a resume and a stop take effect at once.
 */
public final class ProducerRunner implements ProducerHandle {

    private static final Logger LOG = LoggerFactory.getLogger(ProducerRunner.class);

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final ProducerSpec spec;
    private final List<TopicSpec> targets;
    private final Runnable onChange;
    private final AtomicBoolean paused = new AtomicBoolean();

    /** The loop thread, or {@code null} while the producer is not started. */
    private volatile Thread thread;
    private volatile boolean running;

    /**
     * @param spec the description of the producer, with its id assigned
     * @param targets the topics of the spec, with their partition counts
     * @param onChange called, from any thread, when the state of the producer changed
     */
    public ProducerRunner(ProducerSpec spec, List<TopicSpec> targets, Runnable onChange) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.targets = List.copyOf(targets);
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    public ProducerSpec spec() {
        return spec;
    }

    /** The name of the loop thread, after the {@code kaos-} prefix. It is also the client id. */
    public String threadName() {
        return "producer-" + spec.id();
    }

    @Override
    public String id() {
        return spec.id();
    }

    @Override
    public List<String> topics() {
        return spec.topics();
    }

    @Override
    public double ratePerPartition() {
        return spec.ratePerPartition();
    }

    @Override
    public ProducerState state() {
        if (thread == null) {
            return ProducerState.STOPPED;
        }
        return paused.get() ? ProducerState.PAUSED : ProducerState.RUNNING;
    }

    public ProducerSnapshot snapshot() {
        return new ProducerSnapshot(spec.id(), spec.topics(), state(), spec.ratePerPartition());
    }

    @Override
    public void pause() {
        if (paused.compareAndSet(false, true)) {
            onChange.run();
        }
    }

    @Override
    public void resume() {
        if (paused.compareAndSet(true, false)) {
            LockSupport.unpark(thread);
            onChange.run();
        }
    }

    /**
     * Creates the Kafka producer and starts the loop. The undo actions are registered on
     * {@code resources}; closing it stops the loop and closes the producer. The caller
     * serializes the starts and the stops.
     */
    public void start(Supplier<Producer<String, String>> clients, Threads threads, Resources resources) {
        var producer = clients.get();
        resources.register("producer client of " + spec.id(), () -> producer.close(CLOSE_TIMEOUT));

        running = true;
        var started = threads.newThread(threadName(), () -> run(producer));
        // Published before the thread starts, so that a resume() always finds the thread to
        // unpark: once the loop runs, an unpark that comes before its park is kept, and a
        // resume() that comes before the loop runs is seen through the flag.
        thread = started;
        try {
            started.start();
        } catch (Throwable e) {
            thread = null;
            throw e;
        }
        resources.register("producer thread of " + spec.id(), () -> stop(started));
        onChange.run();
    }

    private void stop(Thread started) throws InterruptedException {
        running = false;
        thread = null;
        onChange.run();
        LockSupport.unpark(started);
        if (!started.join(STOP_TIMEOUT)) {
            // The loop is blocked in the Kafka producer, whose blocking calls are interruptible.
            started.interrupt();
            if (!started.join(STOP_TIMEOUT)) {
                throw new SimulationException("The thread " + started.getName() + " did not stop within " + STOP_TIMEOUT);
            }
        }
    }

    /** Runs the loop. An unexpected error ends it: it is logged and the producer shows as stopped. */
    private void run(Producer<String, String> producer) {
        try {
            loop(producer);
        } catch (RuntimeException e) {
            LOG.error("Producer {} stopped on an unexpected error", spec.id(), e);
            thread = null;
            onChange.run();
        }
    }

    private void loop(Producer<String, String> producer) {
        var pacer = new Pacer(spec.ratePerPartition(), System.nanoTime());
        long round = 0;
        while (running && !Thread.currentThread().isInterrupted()) {
            if (paused.get()) {
                LockSupport.park(this);
                pacer.reset(System.nanoTime());
                continue;
            }
            var delay = pacer.delayNanos(System.nanoTime());
            if (delay > 0) {
                // The flags are read again after the wait, whatever ended it.
                LockSupport.parkNanos(this, delay);
                continue;
            }
            writeRound(producer, round++);
            pacer.advance(System.nanoTime());
        }
    }

    /** Writes one record to every partition. A failure ends the round; the next tick tries again. */
    private void writeRound(Producer<String, String> producer, long round) {
        try {
            for (var topic : targets) {
                for (int partition = 0; partition < topic.partitions(); partition++) {
                    if (!running) {
                        return;
                    }
                    var record = new ProducerRecord<>(topic.name(), partition, "key-" + partition, "value-" + round);
                    producer.send(record, (metadata, error) -> {
                        if (error != null) {
                            LOG.debug("Producer {} could not write to {}", spec.id(), topic.name(), error);
                        }
                    });
                }
            }
        } catch (KafkaException | IllegalStateException e) {
            if (running) {
                LOG.warn("Producer {} could not write round {}", spec.id(), round, e);
            }
        }
    }
}
