package io.github.dajac.kaos.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.github.dajac.kaos.SimulationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ResourcesTest {

    private final List<String> undone = new ArrayList<>();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void undoesInReverseOrder() {
        var resources = new Resources("test");
        resources.register("first", () -> undone.add("first"));
        resources.register("second", () -> undone.add("second"));
        resources.register("third", () -> undone.add("third"));

        resources.close();

        assertThat(undone).containsExactly("third", "second", "first");
    }

    @Test
    void undoesAChildAtItsPositionInTheParent() {
        var resources = new Resources("test");
        resources.register("proxy", () -> undone.add("proxy"));
        var topic = resources.child("topic");
        resources.register("after the topic", () -> undone.add("after the topic"));
        // Registered last, and still undone at the position of the child.
        topic.register("create", () -> undone.add("topic create"));
        topic.register("configure", () -> undone.add("topic configure"));

        resources.close();

        assertThat(undone).containsExactly("after the topic", "topic configure", "topic create", "proxy");
    }

    @Test
    void aStepThatSucceedsKeepsItsStackInTheParent() {
        var resources = new Resources("test");
        resources.register("proxy", () -> undone.add("proxy"));

        var topic = resources.start("topic", stack -> stack.register("create", () -> undone.add("topic")));

        assertThat(undone).isEmpty();
        topic.register("later", () -> undone.add("later"));
        resources.close();
        assertThat(undone).containsExactly("later", "topic", "proxy");
    }

    @Test
    void aStepThatFailsIsUndoneAtOnceAndLeavesTheParentUnchanged() {
        var resources = new Resources("test");
        resources.register("proxy", () -> undone.add("proxy"));
        var failure = new IllegalStateException("step failed");

        var thrown = catchThrowable(() -> resources.start("group", stack -> {
            stack.register("configs", () -> undone.add("configs"));
            stack.register("broken", () -> {
                throw new IOException("undo failed");
            });
            throw failure;
        }));

        assertThat(thrown).isSameAs(failure);
        assertThat(thrown.getSuppressed()).singleElement().isInstanceOf(IOException.class);
        assertThat(undone).containsExactly("configs");
        resources.close();
        assertThat(undone).containsExactly("configs", "proxy");
    }

    @Test
    void aChildClosedOnItsOwnIsNotUndoneAgainByTheParent() {
        var resources = new Resources("test");
        resources.register("proxy", () -> undone.add("proxy"));
        var topic = resources.child("topic");
        topic.register("create", () -> undone.add("topic"));

        topic.close();
        assertThat(undone).containsExactly("topic");

        resources.close();
        assertThat(undone).containsExactly("topic", "proxy");
    }

    @Test
    void runsEveryUndoAndReportsTheFailuresOnASingleException() {
        var resources = new Resources("test");
        var firstFailure = new IOException("first");
        var childFailure = new IllegalStateException("child");
        resources.register("first", () -> {
            undone.add("first");
            throw firstFailure;
        });
        var child = resources.child("child");
        child.register("child", () -> {
            undone.add("child");
            throw childFailure;
        });
        resources.register("third", () -> undone.add("third"));

        var thrown = catchThrowable(resources::close);

        assertThat(undone).containsExactly("third", "child", "first");
        // The failure of the child is not nested in an exception of its own.
        assertThat(thrown).isExactlyInstanceOf(SimulationException.class)
            .hasMessageContaining("2 undo action(s) of test failed");
        assertThat(thrown.getSuppressed()).containsExactly(childFailure, firstFailure);
    }

    @Test
    void attachesTheUndoFailuresToTheFailureOfTheStep() {
        var resources = new Resources("test");
        var undoFailure = new IOException("undo");
        resources.register("first", () -> undone.add("first"));
        resources.register("second", () -> {
            throw undoFailure;
        });
        var stepFailure = new SimulationException("the third step failed");

        resources.close(stepFailure);

        assertThat(undone).containsExactly("first");
        assertThat(stepFailure.getSuppressed()).containsExactly(undoFailure);
    }

    @Test
    void closeIsIdempotent() {
        var resources = new Resources("test");
        resources.register("failing", () -> {
            undone.add("failing");
            throw new IOException("once");
        });

        assertThatThrownBy(resources::close).isInstanceOf(SimulationException.class);
        assertThatCode(resources::close).doesNotThrowAnyException();

        assertThat(undone).containsExactly("failing");
    }

    @Test
    void refusesAStepAfterTheClose() {
        var resources = new Resources("test");
        resources.close();

        assertThatThrownBy(() -> resources.register("late", () -> {})).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resources.child("late")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aStepThatFailedHasNoUndo() {
        var resources = new Resources("test");
        resources.register("first", () -> undone.add("first"));
        // The second step fails, so its undo is never registered.
        resources.close(new SimulationException("the second step failed"));

        assertThat(undone).containsExactly("first");
    }

    @Test
    void anInterruptDoesNotSkipTheUndoActionsAndIsRestored() {
        var resources = new Resources("test");
        var sawInterrupt = new ArrayList<Boolean>();
        resources.register("first", () -> sawInterrupt.add(Thread.currentThread().isInterrupted()));
        resources.register("second", () -> {
            sawInterrupt.add(Thread.currentThread().isInterrupted());
            throw new InterruptedException("second");
        });
        resources.register("third", () -> sawInterrupt.add(Thread.currentThread().isInterrupted()));

        Thread.currentThread().interrupt();
        var thrown = catchThrowable(resources::close);

        assertThat(sawInterrupt).containsExactly(false, false, false);
        assertThat(thrown.getSuppressed()).singleElement().isInstanceOf(InterruptedException.class);
        assertThat(Thread.interrupted()).isTrue();
    }
}
