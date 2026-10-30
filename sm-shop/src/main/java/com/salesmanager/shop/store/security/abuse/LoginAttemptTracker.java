package com.salesmanager.shop.store.security.abuse;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Counts failed logins and locks for a short time after too many failures in
 * a window. Two counters run side by side:
 *
 * <ul>
 * <li>per account and client address: a low threshold (default 5 in 15
 * minutes), so one address guessing at an account is stopped quickly without
 * locking the owner out when they sign in from somewhere else;</li>
 * <li>per account alone: a higher threshold (default 20 in 15 minutes), so
 * guessing spread over many addresses is capped too. While this lock holds,
 * every attempt on the account fails, from any address.</li>
 * </ul>
 *
 * Unknown usernames are counted exactly like known ones, so the lockout itself
 * does not reveal whether an account exists. While locked, every attempt fails
 * with the same response as a wrong password, including one with the right
 * password. A successful login clears the per-address counter only: the
 * account-wide count runs out with its window, so a login by the owner does
 * not hand a distributed attacker a fresh budget. State is in memory and per
 * instance.
 */
public class LoginAttemptTracker {

	/** Upper bound on tracked keys per counter; the least recently used key is dropped first. */
	static final int MAX_KEYS = 100_000;

	private final Counter perAddress;
	private final Counter perAccount;
	private final LongSupplier clock;

	public LoginAttemptTracker(int maxFailures, long windowSeconds, long lockSeconds, int accountMaxFailures,
			long accountLockSeconds) {
		this(maxFailures, windowSeconds, lockSeconds, accountMaxFailures, accountLockSeconds,
				System::currentTimeMillis);
	}

	LoginAttemptTracker(int maxFailures, long windowSeconds, long lockSeconds, int accountMaxFailures,
			long accountLockSeconds, LongSupplier clock) {
		this.perAddress = new Counter(maxFailures, windowSeconds, lockSeconds);
		this.perAccount = new Counter(accountMaxFailures, windowSeconds, accountLockSeconds);
		this.clock = clock;
	}

	/** The account part of a key: realm plus the trimmed, lower-cased username. */
	public static String key(String realm, String username) {
		return realm + ":" + (username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
	}

	public boolean isLocked(String account, String address) {
		long now = clock.getAsLong();
		return perAccount.isLocked(account, now) || perAddress.isLocked(addressKey(account, address), now);
	}

	public void recordFailure(String account, String address) {
		long now = clock.getAsLong();
		perAddress.recordFailure(addressKey(account, address), now);
		perAccount.recordFailure(account, now);
	}

	public void recordSuccess(String account, String address) {
		perAddress.remove(addressKey(account, address));
	}

	int trackedAddressKeys() {
		return perAddress.size();
	}

	private static String addressKey(String account, String address) {
		return account + "|" + (address == null ? "" : address);
	}

	/**
	 * Failure counts per key. When full, the least recently used key is
	 * dropped, so filling the map with junk usernames cannot switch the lockout
	 * off for new keys; an account under active attack is looked up on every
	 * attempt and stays recent.
	 */
	private static final class Counter {

		private final int maxFailures;
		private final long windowMillis;
		private final long lockMillis;

		private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<String, Entry>(256, 0.75f, true) {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
				return size() > MAX_KEYS;
			}
		};

		Counter(int maxFailures, long windowSeconds, long lockSeconds) {
			if (maxFailures < 1 || windowSeconds < 1 || lockSeconds < 1) {
				throw new IllegalArgumentException("lockout settings must be at least 1");
			}
			this.maxFailures = maxFailures;
			this.windowMillis = windowSeconds * 1000L;
			this.lockMillis = lockSeconds * 1000L;
		}

		synchronized boolean isLocked(String key, long now) {
			Entry e = entries.get(key);
			return e != null && e.lockedUntil > now;
		}

		synchronized void recordFailure(String key, long now) {
			Entry e = entries.get(key);
			if (e == null) {
				e = new Entry(now);
				entries.put(key, e);
			}
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

		synchronized void remove(String key) {
			entries.remove(key);
		}

		synchronized int size() {
			return entries.size();
		}
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
