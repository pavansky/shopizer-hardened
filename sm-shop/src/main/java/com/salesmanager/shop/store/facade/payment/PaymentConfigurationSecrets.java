package com.salesmanager.shop.store.facade.payment;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.salesmanager.core.model.system.IntegrationConfiguration;

/**
 * Rules that keep gateway secrets write-only through the API.
 * <ul>
 * <li>On read, every integration key whose name looks secret is replaced by
 * {@code ****} and the last 4 characters (or only {@code ****} for values of 8
 * characters or fewer, where 4 characters would give away too much).</li>
 * <li>On write, a secret key whose value is still masked means "unchanged": the
 * stored value is kept.</li>
 * </ul>
 */
public final class PaymentConfigurationSecrets {

	public static final String MASK = "****";
	private static final int VISIBLE = 4;
	private static final int MIN_LENGTH_TO_SHOW_SUFFIX = 9;
	private static final Pattern SECRET_NAME = Pattern
			.compile("(?i).*(key|secret|password|passwd|token|signature).*");

	private PaymentConfigurationSecrets() {
	}

	public static boolean isSecretName(String name) {
		return name != null && SECRET_NAME.matcher(name).matches();
	}

	public static boolean isMasked(String value) {
		return value != null && value.startsWith(MASK);
	}

	public static String mask(String value) {
		if (value == null || value.isEmpty()) {
			return value;
		}
		if (value.length() < MIN_LENGTH_TO_SHOW_SUFFIX) {
			return MASK;
		}
		return MASK + value.substring(value.length() - VISIBLE);
	}

	/** Copy of the keys with every secret value masked. */
	public static Map<String, String> maskKeys(Map<String, String> keys) {
		if (keys == null) {
			return new HashMap<>();
		}
		Map<String, String> masked = new HashMap<>();
		keys.forEach((name, value) -> masked.put(name, isSecretName(name) ? mask(value) : value));
		return masked;
	}

	/**
	 * The keys to store: the submitted ones, except that a secret submitted in its
	 * masked form keeps the stored value. A masked value with nothing stored behind it
	 * is dropped, so module validation reports it as missing.
	 */
	public static Map<String, String> mergeKeys(Map<String, String> submitted, Map<String, String> stored) {
		Map<String, String> current = stored == null ? Collections.emptyMap() : stored;
		Map<String, String> merged = new HashMap<>();
		if (submitted == null) {
			return merged;
		}
		submitted.forEach((name, value) -> {
			if (isSecretName(name) && isMasked(value)) {
				if (current.containsKey(name)) {
					merged.put(name, current.get(name));
				}
			} else {
				merged.put(name, value);
			}
		});
		return merged;
	}

	/**
	 * Names of the settings that differ between two configurations, sorted. Integration
	 * keys appear by name, options as {@code options.<name>}, plus {@code active} and
	 * {@code defaultSelected}. Never contains values. A null previous configuration
	 * counts as an empty, inactive one.
	 */
	public static Set<String> changedSettings(IntegrationConfiguration previous, IntegrationConfiguration next) {
		Set<String> changed = new TreeSet<>();
		Map<String, String> oldKeys = previous == null ? null : previous.getIntegrationKeys();
		changed.addAll(changedNames(oldKeys, next.getIntegrationKeys()));
		Map<String, List<String>> oldOptions = previous == null ? null : previous.getIntegrationOptions();
		for (String name : changedNames(oldOptions, next.getIntegrationOptions())) {
			changed.add("options." + name);
		}
		if ((previous != null && previous.isActive()) != next.isActive()) {
			changed.add("active");
		}
		if ((previous != null && previous.isDefaultSelected()) != next.isDefaultSelected()) {
			changed.add("defaultSelected");
		}
		return changed;
	}

	private static <V> Set<String> changedNames(Map<String, V> before, Map<String, V> after) {
		Map<String, V> a = before == null ? Collections.emptyMap() : before;
		Map<String, V> b = after == null ? Collections.emptyMap() : after;
		Set<String> names = new HashSet<>(a.keySet());
		names.addAll(b.keySet());
		names.removeIf(name -> Objects.equals(a.get(name), b.get(name)));
		return names;
	}

	/** Copy of a configuration's comparable state, taken before it is modified. */
	static IntegrationConfiguration snapshot(IntegrationConfiguration source) {
		if (source == null) {
			return null;
		}
		IntegrationConfiguration copy = new IntegrationConfiguration();
		copy.setModuleCode(source.getModuleCode());
		copy.setActive(source.isActive());
		copy.setDefaultSelected(source.isDefaultSelected());
		copy.setIntegrationKeys(source.getIntegrationKeys() == null ? null : new HashMap<>(source.getIntegrationKeys()));
		copy.setIntegrationOptions(
				source.getIntegrationOptions() == null ? null : new HashMap<>(source.getIntegrationOptions()));
		return copy;
	}
}
