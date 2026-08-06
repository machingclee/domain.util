package com.machingclee.domain.util.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RequestSequenceTest {

    @AfterEach
    void tearDown() {
        RequestSequence.clear();
        MDC.clear();
    }

    @Test
    void nextUsesMdcRequestId() {
        MDC.put(MdcContextKeys.REQUEST_ID, "req-mdc");
        assertEquals(1, RequestSequence.next());
        assertEquals(2, RequestSequence.next());
    }

    @Test
    void nextWithExplicitRequestIdContinuesAfterMdcCleared() {
        String requestId = "req-post-commit";
        MDC.put(MdcContextKeys.REQUEST_ID, requestId);

        // Command audit row (while MDC is set)
        assertEquals(1, RequestSequence.next());

        // Simulates SpringDomainEventDispatcher.withContext finally clearing MDC
        // before a deferred POST_COMMIT DomainEventLogger run.
        MDC.clear();

        // Domain event audit row must continue the same counter, not restart at 1
        assertEquals(2, RequestSequence.next(requestId));
        assertEquals(3, RequestSequence.next(requestId));
    }

    @Test
    void blankRequestIdFallsBackToThreadLocalAndIsIndependentOfMap() {
        MDC.put(MdcContextKeys.REQUEST_ID, "req-map");
        assertEquals(1, RequestSequence.next());

        MDC.clear();
        assertEquals(1, RequestSequence.next((String) null));
        assertEquals(2, RequestSequence.next(""));

        // Map counter for req-map is still at 1 → next is 2
        assertEquals(2, RequestSequence.next("req-map"));
    }

    @Test
    void differentRequestIdsHaveIndependentCounters() {
        assertEquals(1, RequestSequence.next("a"));
        assertEquals(1, RequestSequence.next("b"));
        assertEquals(2, RequestSequence.next("a"));
        assertNotEquals(RequestSequence.next("a"), RequestSequence.next("b"));
    }
}
