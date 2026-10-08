package com.machingclee.domain.util.common.bytecodescanner;

import com.machingclee.domain.util.common.event.ExternalEventPublisher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalEventScannerTest {

    private final ExternalEventPublisher publisher = new ExternalEventPublisher(event -> {
    });

    @Test
    void scansEventBuiltInline() {
        List<Class<?>> events = ExternalEventScanner.scan(InlinePublish.class);
        assertEquals(Set.of(AlphaEvent.class), asSet(events));
    }

    @Test
    void scansEventAssignedToALocalFirst() {
        // The shape the websocket handler uses: build into a variable, then publish.
        List<Class<?>> events = ExternalEventScanner.scan(LocalVariablePublish.class);
        assertEquals(Set.of(AlphaEvent.class), asSet(events));
    }

    @Test
    void scansTransactionalPublish() {
        List<Class<?>> events = ExternalEventScanner.scan(TransactionalPublish.class);
        assertTrue(asSet(events).contains(BetaEvent.class));
    }

    @Test
    void referenceCheckAgreesWithTheScan() {
        assertTrue(ExternalEventScanner.referencesPublisher(InlinePublish.class));
        assertFalse(ExternalEventScanner.referencesPublisher(String.class));
    }

    @Test
    void ignoresUnresolvableArguments() {
        // A cast to Object carries no type, so it must not be reported as one.
        List<Class<?>> events = ExternalEventScanner.scan(UnresolvablePublish.class);
        assertTrue(events.isEmpty(), "expected nothing resolvable, got " + events);
    }

    private static Set<Class<?>> asSet(List<Class<?>> classes) {
        return classes.stream().collect(Collectors.toSet());
    }

    static class AlphaEvent {
        final String value;

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
    }

    class InlinePublish {
        void handle() {
            publisher.publish(AlphaEvent.builder().value("x").build());
        }
    }

    class LocalVariablePublish {
        void handle() {
            var event = AlphaEvent.builder().value("x").build();
            publisher.publish(event);
        }
    }

    class TransactionalPublish {
        void handle() {
            publisher.publishTransactional(new BetaEvent());
        }
    }

    class UnresolvablePublish {
        void handle(Object raw) {
            publisher.publish((Object) raw);
        }
    }
}
