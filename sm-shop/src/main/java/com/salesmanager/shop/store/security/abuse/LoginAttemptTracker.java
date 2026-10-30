package com.salesmanager.shop.store.security.abuse;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Counts failed logins per account and locks the account for a short time
 * after too many failures in a window.
 *
 * Unknown usernames are counted exactly like known ones, so the lockout itself
 * does not reveal whether an account exists. While an account is locked every
 * attempt fails with the same response as a wrong password, including one with
 * the right password. State is in memory and per instance.
 */
public class LoginAttemptTracker {

	static final int MAX_KEYS = 100_000;

	private final int maxFailures;
	private final long windowMillis;
	private final long lockMillis;
	private final LongSupplier clock;
	private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

	public LoginAttemptTracker(int maxFailures, long windowSeconds, long lockSeconds) {
		this(maxFailures, windowSeconds, lockSeconds, System::currentTimeMillis);
	}

	LoginAttemptTracker(int maxFailures, long windowSeconds, long lockSeconds, LongSupplier clock) {
		if (maxFailures < 1 || windowSeconds < 1 || lockSeconds < 1) {
			throw new IllegalArgumentException("lockout settings must be at least 1");
		}
		this.maxFailures = maxFailures;
		this.windowMillis = windowSeconds * 1000L;
		this.lockMillis = lockSeconds * 1000L;
		this.clock = clock;
	}

	public static String key(String realm, String username) {
		return realm + ":" + (username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
	}

	public boolean isLocked(String key) {
		Entry e = entries.get(key);
		if (e == null) {
			return false;
		}
		synchronized (e) {
			return e.lockedUntil > clock.getAsLong();
		}
	}

	public void recordFailure(String key) {
		long now = clock.getAsLong();
		if (entries.size() >= MAX_KEYS && !entries.containsKey(key)) {
			evictExpired(now);
			if (entries.size() >= MAX_KEYS) {
				return;
			}
		}
		Entry e = entries.computeIfAbsent(key, k -> new Entry(now));
		synchronized (e) {
			if (now - e.windowStart > windowMillis) {
				e.windowStart = now;
				e.failures = 0;
			}
			e.failures++;
			if (e.failures >= maxFailures) {
				e.lockedUntil = now + lockMillis;
				e.failures = 0;
				e.windowStart = now;
			}
		}
	}

	public void recordSuccess(String key) {
		entries.remove(key);
	}

	private void evictExpired(long now) {
		entries.entrySet().removeIf(en -> {
			Entry e = en.getValue();
			synchronized (e) {
				return e.lockedUntil <= now && now - e.windowStart > windowMillis;
			}
		});
	}

	private static final class Entry {
		int failures;
		long windowStart;
		long lockedUntil;

		Entry(long now) {
			this.windowStart = now;
		}
	}
}
