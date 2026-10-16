package net.nulltrace.mod.privacy;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ledger behaviour the audit output depends on: bounded growth, per-surface counting (not
 * per-event), and thread safety under the concurrent writers the mixins create.
 */
class AuditLedgerTest {

    private static PrivacyDecision decision(PrivacySurface surface, PrivacyAction action) {
        return PrivacyDecision.of(surface, "subject", action, "test reason");
    }

    @Test
    void beginReplacesTheContextAndClearsEvents() {
        AuditLedger ledger = new AuditLedger();
        ledger.begin(ConnectionContext.builder("first.example").build());
        ledger.record(decision(PrivacySurface.CLIENT_BRAND, PrivacyAction.BLOCK));
        assertEquals(1, ledger.events().size());

        ledger.begin(ConnectionContext.builder("second.example").build());
        assertEquals("second.example", ledger.context().server());
        assertTrue(ledger.events().isEmpty(), "a new connection starts with a clean ledger");
    }

    @Test
    void repeatedEventsDoNotInflateExposure() {
        AuditLedger ledger = new AuditLedger();
        ledger.begin(ConnectionContext.builder("noisy.example").build());
        // A server probing the same surface 500 times is still one exposed surface.
        for (int i = 0; i < 500; i++) {
            ledger.record(decision(PrivacySurface.TRANSLATION_PROBE, PrivacyAction.ALLOW));
        }
        ledger.record(decision(PrivacySurface.SERVER_COOKIE, PrivacyAction.WARN));

        assertEquals(2, ledger.allowedSurfaceCount());
        assertEquals(501, ledger.remainingExposure(),
                "the deprecated event count is kept for compatibility and is documented as misleading");
    }

    @Test
    void blockedSurfacesDoNotCountAsExposure() {
        AuditLedger ledger = new AuditLedger();
        ledger.begin(ConnectionContext.builder("strict.example").build());
        ledger.record(decision(PrivacySurface.CLIENT_BRAND, PrivacyAction.BLOCK));
        ledger.record(decision(PrivacySurface.MOD_CHANNEL, PrivacyAction.BLOCK));
        assertEquals(0, ledger.allowedSurfaceCount());
    }

    @Test
    void logCountsAsExposureBecauseTheServerStillReceivesIt() {
        AuditLedger ledger = new AuditLedger();
        ledger.begin(ConnectionContext.builder("log.example").build());
        ledger.record(decision(PrivacySurface.CLIENT_INFORMATION, PrivacyAction.LOG));
        assertEquals(1, ledger.allowedSurfaceCount());
    }

    @Test
    void ledgerIsBounded() {
        AuditLedger ledger = new AuditLedger();
        ledger.begin(ConnectionContext.builder("flood.example").build());
        for (int i = 0; i < 5000; i++) {
            ledger.record(decision(PrivacySurface.RESOURCE_PACK, PrivacyAction.BLOCK));
        }
        assertTrue(ledger.events().size() <= 2048,
                "an unbounded ledger would grow until the client runs out of memory");
        assertEquals(5000, ledger.count(PrivacyAction.BLOCK),
                "the counters keep counting even after old events are dropped");
    }

    @Test
    void decisionsAreCountedPerActionAndSurface() {
        AuditLedger ledger = new AuditLedger();
        ledger.begin(ConnectionContext.builder("count.example").build());
        ledger.record(decision(PrivacySurface.RESOURCE_PACK, PrivacyAction.BLOCK));
        ledger.record(decision(PrivacySurface.RESOURCE_PACK, PrivacyAction.BLOCK));
        ledger.record(decision(PrivacySurface.RESOURCE_PACK, PrivacyAction.ALLOW));
        ledger.record(decision(PrivacySurface.LOCAL_REQUEST, PrivacyAction.BLOCK));

        assertEquals(3, ledger.count(PrivacyAction.BLOCK));
        assertEquals(1, ledger.count(PrivacyAction.ALLOW));
        assertEquals(0, ledger.count(PrivacyAction.WARN));
        assertEquals(2, ledger.counts(PrivacyAction.BLOCK).get(PrivacySurface.RESOURCE_PACK));
        assertEquals(1, ledger.counts(PrivacyAction.BLOCK).get(PrivacySurface.LOCAL_REQUEST));
    }

    @Test
    void nullDecisionsAndSnapshotMutationAreIgnored() {
        AuditLedger ledger = new AuditLedger();
        ledger.begin(ConnectionContext.builder("null.example").build());
        ledger.record(null);
        assertEquals(0, ledger.events().size());
        ledger.record(decision(PrivacySurface.CLIENT_BRAND, PrivacyAction.BLOCK));

        var snapshot = ledger.events();
        assertFalse(snapshot.isEmpty());
        try {
            snapshot.add(decision(PrivacySurface.CLIENT_BRAND, PrivacyAction.ALLOW));
            org.junit.jupiter.api.Assertions.fail("events() must not hand out the live deque");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    void concurrentWritersDoNotLoseEvents() throws InterruptedException {
        AuditLedger ledger = new AuditLedger();
        ledger.begin(ConnectionContext.builder("race.example").build());
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ledger.record(decision(PrivacySurface.MOD_CHANNEL, PrivacyAction.BLOCK));
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "ledger writers must finish");
        assertEquals(0, errors.get());
        assertEquals(threads * perThread, ledger.count(PrivacyAction.BLOCK),
                "netty threads must not lose audit decisions");
    }
}
