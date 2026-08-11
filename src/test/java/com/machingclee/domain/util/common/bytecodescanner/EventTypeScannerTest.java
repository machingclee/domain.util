package com.machingclee.domain.util.common.bytecodescanner;

import com.machingclee.domain.util.common.interfaces.Command;
import com.machingclee.domain.util.common.interfaces.CommandHandler;
import com.machingclee.domain.util.common.interfaces.EventQueue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventTypeScannerTest {

    @Test
    void scansEventsAddedDirectlyInHandle() {
        List<Class<?>> events = EventTypeScanner.scanEventTypes(new DirectHandleHandler());
        assertEquals(Set.of(AlphaEvent.class), asSet(events));
    }

    @Test
    void scansEventsAddedFromPrivateHelper() {
        // Regression: handlers that emit from helpers (not handle()) left "to": []
        // when the scanner only inspected handle().
        List<Class<?>> events = EventTypeScanner.scanEventTypes(new HelperEmitHandler());
        assertEquals(Set.of(AlphaEvent.class, BetaEvent.class), asSet(events));
    }

    @Test
    void scansBuilderPatternInHelper() {
        List<Class<?>> events = EventTypeScanner.scanEventTypes(new BuilderHelperHandler());
        assertTrue(asSet(events).contains(AlphaEvent.class),
                "expected AlphaEvent from builder().build() in helper, got " + events);
    }

    private static Set<Class<?>> asSet(List<Class<?>> classes) {
        return classes.stream().collect(Collectors.toSet());
    }

    // ── sample domain types ──────────────────────────────────────────

    static class AlphaEvent {
        private final String value;

        AlphaEvent(String value) {
            this.value = value;
        }

        static Builder builder() {
            return new Builder();
        }

        static class Builder {
            private String value;

            Builder value(String value) {
                this.value = value;
                return this;
            }

            AlphaEvent build() {
                return new AlphaEvent(value);
            }
        }
    }

    static class BetaEvent {
        BetaEvent() {
        }
    }

    static class DummyCommand implements Command<Void> {
    }

    static class DirectHandleHandler implements CommandHandler<DummyCommand, Void> {
        @Override
        public Void handle(EventQueue eventQueue, DummyCommand command) {
            eventQueue.add(new AlphaEvent("x"));
            return null;
        }
    }

    static class HelperEmitHandler implements CommandHandler<DummyCommand, Void> {
        @Override
        public Void handle(EventQueue eventQueue, DummyCommand command) {
            emitBoth(eventQueue);
            return null;
        }

        private void emitBoth(EventQueue eventQueue) {
            eventQueue.add(AlphaEvent.builder().value("a").build());
            eventQueue.add(new BetaEvent());
        }
    }

    static class BuilderHelperHandler implements CommandHandler<DummyCommand, Void> {
        @Override
        public Void handle(EventQueue eventQueue, DummyCommand command) {
            emit(eventQueue);
            return null;
        }

        private void emit(EventQueue eventQueue) {
            eventQueue.add(AlphaEvent.builder().value("z").build());
        }
    }
}
