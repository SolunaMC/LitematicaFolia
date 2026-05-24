package fr.ekaii.litematica.protocol.handler;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple per-player token-bucket rate limiter for Servux bulk-request
 * traffic. Cheap to update (no scheduled refill thread): every call to
 * {@link #tryAcquire(UUID)} computes elapsed-time-since-last-refill and
 * tops the bucket up by {@code refillPerSecond * elapsed}.
 *
 * <p>Defaults align with the P12 brief:
 * <ul>
 *   <li>Block-entity queries: capacity 64, refill 64/s.</li>
 *   <li>Entity queries:       capacity 32, refill 32/s.</li>
 * </ul>
 *
 * <p>Thread-safety: the per-player {@link Bucket} is mutated under its
 * own {@code synchronized} guard, so callers can hit the limiter from
 * any thread. The outer map is a {@link ConcurrentHashMap} keyed by
 * player UUID.
 */
public final class RateLimiter {

    private final int capacity;
    private final double refillPerSecond;
    private final ConcurrentHashMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    public boolean tryAcquire(UUID player) {
        Bucket b = buckets.computeIfAbsent(player, k -> new Bucket(capacity));
        synchronized (b) {
            long now = System.nanoTime();
            double elapsed = (now - b.lastRefillNanos) / 1_000_000_000.0;
            b.tokens = Math.min(capacity, b.tokens + elapsed * refillPerSecond);
            b.lastRefillNanos = now;
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    public void forget(UUID player) {
        buckets.remove(player);
    }

    /** Visible for testing — reset all buckets so deterministic tests get a clean slate. */
    public void clear() {
        buckets.clear();
    }

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(int capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }
    }
}
