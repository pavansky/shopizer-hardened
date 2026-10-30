package com.salesmanager.shop.store.facade.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.salesmanager.core.model.system.IntegrationConfiguration;

class PaymentConfigurationSecretsTest {

	private static Map<String, String> keys(String... pairs) {
		Map<String, String> m = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			m.put(pairs[i], pairs[i + 1]);
		}
		return m;
	}

	@Test
	void secretNamesAreRecognised() {
		for (String name : Arrays.asList("secretKey", "publishableKey", "private_key", "tokenization_key", "password",
				"webhookSecret", "signature", "accessKey", "apiToken", "PRIVATEKEY")) {
			assertTrue(PaymentConfigurationSecrets.isSecretName(name), name);
		}
		for (String name : Arrays.asList("address", "merchant_id", "username", "note", "account")) {
			assertFalse(PaymentConfigurationSecrets.isSecretName(name), name);
		}
	}

	@Test
	void maskShowsOnlyLastFourOfLongValues() {
		assertEquals("****1234", PaymentConfigurationSecrets.mask("sk_live_abcdefgh1234"));
		assertEquals("****", PaymentConfigurationSecrets.mask("short123"));
		assertEquals("", PaymentConfigurationSecrets.mask(""));
		assertEquals(null, PaymentConfigurationSecrets.mask(null));
	}

	@Test
	void readMasksSecretsAndLeavesOtherKeys() {
		Map<String, String> masked = PaymentConfigurationSecrets
				.maskKeys(keys("secretKey", "sk_live_abcdefgh1234", "address", "1 Main St"));
		assertEquals("****1234", masked.get("secretKey"));
		assertEquals("1 Main St", masked.get("address"));
	}

	@Test
	void maskedValueOnWriteKeepsStoredSecret() {
		Map<String, String> stored = keys("secretKey", "sk_live_abcdefgh1234", "publishableKey", "pk_live_old_00001111");
		Map<String, String> submitted = keys("secretKey", "****1234", "publishableKey", "pk_live_new_00002222");

		Map<String, String> merged = PaymentConfigurationSecrets.mergeKeys(submitted, stored);

		assertEquals("sk_live_abcdefgh1234", merged.get("secretKey"));
		assertEquals("pk_live_new_00002222", merged.get("publishableKey"));
	}

	@Test
	void maskedValueWithNothingStoredIsDropped() {
		Map<String, String> merged = PaymentConfigurationSecrets.mergeKeys(keys("secretKey", "****1234"), null);
		assertFalse(merged.containsKey("secretKey"));
	}

	@Test
	void maskOnNonSecretKeyIsTakenLiterally() {
		Map<String, String> merged = PaymentConfigurationSecrets.mergeKeys(keys("note", "****"), keys("note", "x"));
		assertEquals("****", merged.get("note"));
	}

	@Test
	void changedSettingsNamesKeysAndFlagsButNoValues() {
		IntegrationConfiguration before = new IntegrationConfiguration();
		before.setIntegrationKeys(keys("secretKey", "a", "publishableKey", "b", "removed", "c"));
		before.setActive(true);
		IntegrationConfiguration after = new IntegrationConfiguration();
		after.setIntegrationKeys(keys("secretKey", "a", "publishableKey", "B", "added", "d"));
		after.setActive(false);
		Map<String, java.util.List<String>> options = new HashMap<>();
		options.put("transaction", Arrays.asList("AUTHORIZE"));
		after.setIntegrationOptions(options);

		Set<String> changed = PaymentConfigurationSecrets.changedSettings(before, after);

		assertEquals(new TreeSet<>(Arrays.asList("active", "added", "options.transaction", "publishableKey", "removed")),
				changed);
	}

	@Test
	void firstConfigurationListsEverythingSet() {
		IntegrationConfiguration after = new IntegrationConfiguration();
		after.setIntegrationKeys(keys("address", "1 Main St"));
		after.setActive(true);
		after.setDefaultSelected(true);

		assertEquals(new TreeSet<>(Arrays.asList("active", "address", "defaultSelected")),
				PaymentConfigurationSecrets.changedSettings(null, after));
	}

	@Test
	void identicalConfigurationHasNoChanges() {
		IntegrationConfiguration a = new IntegrationConfiguration();
		a.setIntegrationKeys(keys("secretKey", "x"));
		assertTrue(PaymentConfigurationSecrets.changedSettings(PaymentConfigurationSecrets.snapshot(a), a).isEmpty());
	}
}
