package com.example.atomikos.support;

import javax.transaction.xa.XAException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Test-only, j-xa-tester-style engine for deterministic XA lifecycle faults.
 */
public class TestXaFaultEngine {

    public enum Operation {
        START, END, PREPARE, COMMIT, ROLLBACK, RECOVER, FORGET, IS_SAME_RM, SET_TIMEOUT, GET_TIMEOUT
    }

    public enum Position {
        BEFORE, AFTER_SUCCESS, AFTER_FAILURE
    }

    public record Event(
            String resourceId,
            Operation operation,
            Position position,
            Boolean onePhase,
            Integer errorCode,
            Instant timestamp) {
    }

    public static class Rule {
        private final Predicate<Event> matcher;
        private final XaAction action;
        private final AtomicBoolean fired = new AtomicBoolean();

        Rule(Predicate<Event> matcher, XaAction action) {
            this.matcher = Objects.requireNonNull(matcher, "matcher");
            this.action = Objects.requireNonNull(action, "action");
        }

        void apply(Event event) throws XAException {
            if (matcher.test(event) && fired.compareAndSet(false, true)) {
                action.execute(event);
            }
        }

        public boolean fired() {
            return fired.get();
        }
    }

    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final List<Rule> rules = new CopyOnWriteArrayList<>();

    public Rule throwOnce(String resourceId, Operation operation, Position position, int errorCode) {
        return addRule(resourceId, operation, position, throwException(errorCode));
    }

    public Rule addRule(String resourceId, Operation operation, Position position, XaAction action) {
        Rule rule = new Rule(event ->
                resourceId.equals(event.resourceId())
                        && operation == event.operation()
                        && position == event.position(), action);
        rules.add(rule);
        return rule;
    }

    public static XaAction throwException(int errorCode) {
        return event -> {
            XAException exception = new XAException(errorCode);
            exception.errorCode = errorCode;
            throw exception;
        };
    }

    public static XaAction callback(Consumer<Event> callback) {
        return event -> callback.accept(event);
    }

    public static XaAction compose(XaAction... actions) {
        return event -> {
            for (XaAction action : actions) {
                action.execute(event);
            }
        };
    }

    public void record(String resourceId, Operation operation, Position position, Boolean onePhase) throws XAException {
        record(resourceId, operation, position, onePhase, null);
    }

    public void recordFailure(String resourceId, Operation operation, Boolean onePhase, XAException exception) {
        events.add(new Event(resourceId, operation, Position.AFTER_FAILURE, onePhase,
                exception.errorCode, Instant.now()));
    }

    public List<Event> events() {
        return new ArrayList<>(events);
    }

    public void reset() {
        events.clear();
        rules.clear();
    }

    private void record(String resourceId, Operation operation, Position position,
                        Boolean onePhase, Integer errorCode) throws XAException {
        Event event = new Event(resourceId, operation, position, onePhase, errorCode, Instant.now());
        events.add(event);
        for (Rule rule : rules) {
            rule.apply(event);
        }

        @FunctionalInterface
        public interface XaAction {
            void execute(Event event) throws XAException;
        }
    }
}
