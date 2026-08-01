package com.machingclee.domain.util.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maintains a per-request sequence counter so that every saved command/event
 * record for a given request gets a deterministic {@code event_order} value:
 * 1, 2, 3, ...
 *
 * <p>The counter is keyed by {@code MDC.get(MdcContextKeys.REQUEST_ID)}. When the same
 * requestId is reused across multiple top-level invocations (e.g. in
 * integration tests that share a static requestId), the counter continues
 * from where it left off instead of restarting at 1.
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} keyed by requestId with
 * {@link AtomicInteger} values. Different requestIds never contend on the
 * same counter entry.
 *
 * <p>To prevent unbounded map growth in long-running production servers,
 * entries are evicted when the map exceeds {@link #MAX_ENTRIES}.
 */
public final class RequestSequence {

    private static final Logger log = LoggerFactory.getLogger(RequestSequence.class);

    /**
     * Maximum number of counter entries before eviction kicks in.
     * Well above what a test suite accumulates, low enough to keep
     * memory bounded in production.
     */
    static final int MAX_ENTRIES = 10_000;

    private static final ConcurrentHashMap<String, AtomicInteger> sequences =
            new ConcurrentHashMap<>();

    private RequestSequence() {
    }

    /**
     * Resets the counter for the current requestId to 0 so that the next
     * call to {@link #next()} returns 1. No-op if no requestId is present
     * in MDC.
     */
    public static void init() {
        String requestId = MDC.get(MdcContextKeys.REQUEST_ID);
        if (requestId != null) {
            sequences.put(requestId, new AtomicInteger(0));
        }
    }

    /**
     * Returns the next sequence number for the current requestId and
     * increments the counter. If the counter does not exist yet it is
     * created with an initial value of 0 before incrementing (so the
     * first call returns 1). Falls back to a per-thread counter when
     * no requestId is present in MDC.
     *
     * @return the next sequence number (1-based)
     */
    public static int next() {
        String requestId = MDC.get(MdcContextKeys.REQUEST_ID);
        if (requestId == null) {
            return threadLocalCounter();
        }
        AtomicInteger counter = sequences.computeIfAbsent(
                requestId, k -> new AtomicInteger(0));
        int seq = counter.incrementAndGet();

        // Evict some entries if the map has grown too large so that
        // long-running production servers don't leak memory. We evict
        // entries other than the current requestId.
        if (seq == 1 && sequences.size() > MAX_ENTRIES) {
            evictExcess();
        }
        return seq;
    }

    /**
     * Called from the {@code finally} block of a top-level command
     * invocation. Does NOT remove the counter entry (so that tests
     * reusing the same requestId get a continuing sequence), but
     * cleans up the thread-local fallback counter.
     */
    public static void clear() {
        threadLocalCounter.remove();
    }

    /**
     * Returns the number of active counters in the map. Useful for
     * monitoring memory usage.
     */
    public static int activeCounterCount() {
        return sequences.size();
    }

    // --- eviction --------------------------------------------------------

    /**
     * Evicts a batch of entries to keep the map under {@link #MAX_ENTRIES}.
     * Only called from {@link #next()} when a new entry is about to be
     * created and the map is already full.
     */
    private static void evictExcess() {
        int toRemove = sequences.size() - MAX_ENTRIES + 500; // headroom
        Iterator<String> it = sequences.keySet().iterator();
        int removed = 0;
        while (it.hasNext() && removed < toRemove) {
            String key = it.next();
            // Don't evict the entry we just created for the current request
            String currentId = MDC.get(MdcContextKeys.REQUEST_ID);
            if (currentId != null && currentId.equals(key)) {
                continue;
            }
            it.remove();
            removed++;
        }
        if (removed > 0) {
            log.debug("RequestSequence evicted {} stale counter entries ({} remaining)",
                    removed, sequences.size());
        }
    }

    // --- thread-local fallback when MDC has no requestId -----------------

    private static final ThreadLocal<AtomicInteger> threadLocalCounter =
            ThreadLocal.withInitial(() -> new AtomicInteger(0));

    private static int threadLocalCounter() {
        return threadLocalCounter.get().incrementAndGet();
    }
}
