package net.nulltrace.mod.detection;

import net.nulltrace.mod.PrivacyLogger;
import net.nulltrace.mod.config.NullTraceConstants;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// TrackPack signature: many hashes mapped to few URLs (POC sends ~24 hashes to one port-0 URL).
// Locality alone is not a signal — legitimate LAN servers exist. Hash count alone is not a signal —
// any multi-pack stack has 100% unique hashes, but with matching URL diversity.
public class TrackPackDetector {

    private static final Deque<RequestRecord> recentRequests = new ArrayDeque<>();
    private static final Set<String> uniqueHashes = new HashSet<>();
    private static final Set<String> uniqueUrls = new HashSet<>();
    private static final Object LOCK = new Object();

    private static final AtomicLong lastRequestTime = new AtomicLong(0);
    private static final AtomicInteger consecutiveRapidRequests = new AtomicInteger(0);
    private static final AtomicBoolean notifiedPatternOnce = new AtomicBoolean(false);

    public record RequestRecord(String url, String hash, long timestamp) {}

    public static void recordRequest(String url, String hash) {
        long now = System.currentTimeMillis();

        synchronized (LOCK) {
            while (!recentRequests.isEmpty() &&
                   (now - recentRequests.peekFirst().timestamp > NullTraceConstants.Detection.DETECTION_WINDOW_MS ||
                    recentRequests.size() >= NullTraceConstants.Limits.MAX_RECENT_REQUESTS)) {
                recentRequests.pollFirst();
            }

            if (recentRequests.isEmpty()) {
                uniqueHashes.clear();
                uniqueUrls.clear();
            }

            if (hash != null && !hash.isEmpty()) {
                if (uniqueHashes.size() >= NullTraceConstants.Limits.MAX_UNIQUE_HASHES) {
                    uniqueHashes.clear();
                }
                uniqueHashes.add(hash);
            }

            if (url != null && !url.isEmpty()) {
                if (uniqueUrls.size() >= NullTraceConstants.Limits.MAX_UNIQUE_HASHES) {
                    uniqueUrls.clear();
                }
                uniqueUrls.add(url);
            }

            recentRequests.addLast(new RequestRecord(url, hash, now));
        }

        long lastTime = lastRequestTime.get();
        if (lastTime > 0 && (now - lastTime) < NullTraceConstants.Detection.RAPID_REQUEST_INTERVAL_MS) {
            consecutiveRapidRequests.incrementAndGet();
        } else {
            consecutiveRapidRequests.set(0);
        }
        lastRequestTime.set(now);

        analyzePatterns();
    }

    private static void analyzePatterns() {
        if (isRapidRequestPattern()) {
            PrivacyLogger.logDetection("TrackPack", "Rapid request pattern: " + consecutiveRapidRequests.get());
        }

        if (isHashProbing()) {
            int hashCount;
            int urlCount;
            synchronized (LOCK) {
                hashCount = uniqueHashes.size();
                urlCount = uniqueUrls.size();
            }
            PrivacyLogger.logDetection("TrackPack",
                "Hash probing: " + hashCount + " hashes across " + urlCount + " URL(s)");
        }
    }

    private static boolean isRapidRequestPattern() {
        if (consecutiveRapidRequests.get() >= NullTraceConstants.Detection.RAPID_REQUEST_THRESHOLD) {
            return true;
        }

        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            long rapidCount = recentRequests.stream()
                .filter(r -> now - r.timestamp < NullTraceConstants.Detection.RAPID_WINDOW_MS)
                .count();
            return rapidCount >= NullTraceConstants.Detection.RAPID_REQUEST_THRESHOLD;
        }
    }

    private static boolean isHashProbing() {
        synchronized (LOCK) {
            if (recentRequests.size() < NullTraceConstants.Detection.MIN_REQUESTS_FOR_HASH_ANALYSIS) {
                return false;
            }
            int hashes = uniqueHashes.size();
            int urls = Math.max(uniqueUrls.size(), 1);
            return hashes >= NullTraceConstants.Detection.UNIQUE_HASH_THRESHOLD
                && (double) hashes / urls >= NullTraceConstants.Detection.HASHES_PER_URL_THRESHOLD;
        }
    }

    public static boolean isFingerprinting() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            long count = recentRequests.stream()
                .filter(r -> now - r.timestamp < NullTraceConstants.Detection.DETECTION_WINDOW_MS)
                .count();
            if (count < NullTraceConstants.Detection.FINGERPRINT_THRESHOLD) return false;
            int hashes = uniqueHashes.size();
            int urls = Math.max(uniqueUrls.size(), 1);
            return hashes >= NullTraceConstants.Detection.UNIQUE_HASH_THRESHOLD
                && (double) hashes / urls >= NullTraceConstants.Detection.HASHES_PER_URL_THRESHOLD;
        }
    }

    public static boolean consumeNotifyPatternOnce() {
        return notifiedPatternOnce.compareAndSet(false, true);
    }

    public static void reset() {
        synchronized (LOCK) {
            recentRequests.clear();
            uniqueHashes.clear();
            uniqueUrls.clear();
        }
        consecutiveRapidRequests.set(0);
        lastRequestTime.set(0);
        notifiedPatternOnce.set(false);
    }
}
