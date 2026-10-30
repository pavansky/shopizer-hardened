package com.salesmanager.shop.store.security.abuse;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * In-memory token buckets, one per key (for example a client address).
 *
 * Each bucket holds up to {@code capacity} tokens and refills continuously at
 * {@code capacity} tokens per {@code periodSeconds}. Limits are per instance:
 * with N instances behind a load balancer the effective limit is N times the
 * configured one, which is acceptable for abuse throttling.
 */
public class TokenBucketLimiter {

	/** Upper bound on tracked keys, so a flood of distinct addresses cannot exhaust memory. */
	static final int MAX_KEYS = 100_000;

	private final long capacity;
	private final double tokensPerNano;
	private final LongSupplier nanoClock;
	private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

	public TokenBucketLimiter(long capacity, long periodSeconds) {
		this(capacity, periodSeconds, System::nanoTime);
	}

	TokenBucketLimiter(long capacity, long periodSeconds, LongSupplier nanoClock) {
		if (capacity < 1 || periodSeconds < 1) {
			throw new IllegalArgumentException("capacity and period must be at least 1");
		}
		this.capacity = capacity;
		this.tokensPerNano = (double) capacity / (periodSeconds * 1_000_000_000d);
		this.nanoClock = nanoClock;
	}

	/**
	 * Takes one token for the key.
	 *
	 * @return 0 when the call is allowed, otherwise the number of whole seconds
	 *         (at least 1) until a token is available
	 */
	public long tryConsume(String key) {
		long now = nanoClock.getAsLong();
		if (buckets.size() >= MAX_KEYS && !buckets.containsKey(key)) {
			evictFull(now);
		}
		Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
		synchronized (bucket) {
			bucket.refill(now, capacity, tokensPerNano);
			if (bucket.tokens >= 1d) {
				bucket.tokens -= 1d;
				return 0;
			}
			double missingNanos = (1d - bucket.tokens) / tokensPerNano;
			return Math.max(1L, (long) Math.ceil(missingNanos / 1_000_000_000d));
		}
	}

	/** Buckets that have refilled completely carry no state worth keeping. */
	private void evictFull(long now) {
		buckets.entrySet().removeIf(e -> {
			Bucket b = e.getValue();
			synchronized (b) {
				b.refill(now, capacity, tokensPerNano);
				return b.tokens >= capacity;
			}
		});
	}

	int trackedKeys() {
		return buckets.size();
	}

	private static final class Bucket {
		double tokens;
		long updatedAt;

		Bucket(long capacity, long now) {
			this.tokens = capacity;
			this.updatedAt = now;
		}

		void refill(long now, long capacity, double tokensPerNano) {
			long elapsed = now - updatedAt;
			if (elapsed > 0) {
				tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
				updatedAt = now;
			}
		}
	}
}
