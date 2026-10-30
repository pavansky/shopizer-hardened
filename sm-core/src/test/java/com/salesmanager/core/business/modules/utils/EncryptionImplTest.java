package com.salesmanager.core.business.modules.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;

public class EncryptionImplTest {

	private static final String LEGACY_KEY = "7070200000000007";
	private static final String KEY = Base64.getEncoder()
			.encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
	private static final String CONFIG = "{\"stripe\":{\"integrationKeys\":{\"secretKey\":\"sk_test_1234\"}}}";

	private static EncryptionImpl encryption(String key, String legacyKey) {
		EncryptionImpl e = new EncryptionImpl();
		e.setEncryptionKey(key);
		e.setSecretKey(legacyKey);
		e.afterPropertiesSet();
		return e;
	}

	/** The scheme used before v2: AES-CBC, fixed IV, hex output. */
	private static String legacyEncrypt(String value) throws Exception {
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(LEGACY_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
				new IvParameterSpec("fedcba9876543210".getBytes(StandardCharsets.UTF_8)));
		StringBuilder hex = new StringBuilder();
		for (byte b : cipher.doFinal(value.getBytes(StandardCharsets.UTF_8))) {
			hex.append(String.format("%02x", b & 0xFF));
		}
		return hex.toString();
	}

	@Test
	public void gcmRoundTripUsesVersionPrefixAndFreshIv() throws Exception {
		EncryptionImpl e = encryption(KEY, LEGACY_KEY);

		String first = e.encrypt(CONFIG);
		String second = e.encrypt(CONFIG);

		assertTrue(first.startsWith("v2:"));
		assertNotEquals("same plaintext must not give the same ciphertext", first, second);
		assertEquals(CONFIG, e.decrypt(first));
		assertEquals(CONFIG, e.decrypt(second));
	}

	@Test
	public void tamperedCiphertextIsRejected() throws Exception {
		EncryptionImpl e = encryption(KEY, LEGACY_KEY);
		byte[] raw = Base64.getDecoder().decode(e.encrypt(CONFIG).substring(3));
		raw[raw.length - 1] ^= 1;
		try {
			e.decrypt("v2:" + Base64.getEncoder().encodeToString(raw));
			fail("tampered value decrypted");
		} catch (javax.crypto.AEADBadTagException expected) {
			// authentication tag check failed
		}
	}

	@Test
	public void legacyCbcValueStillDecryptsAndIsRewrittenAsV2() throws Exception {
		EncryptionImpl e = encryption(KEY, LEGACY_KEY);
		String legacy = legacyEncrypt(CONFIG);

		String plain = e.decrypt(legacy);
		assertEquals(CONFIG, plain);
		// the next save goes through encrypt, which always writes v2
		assertTrue(e.encrypt(plain).startsWith("v2:"));
	}

	/**
	 * Fixed vector written by the pre-v2 EncryptionImpl on main with secretKey=7070200000000007
	 * (the shipped default). It matches openssl aes-128-cbc with IV "fedcba9876543210", and
	 * guards against the legacy decrypt path drifting together with the helper above.
	 */
	private static final String MAIN_BRANCH_CBC_HEX = "df02614ab6600db0eb4d78e2c4df5574f9ba1903c78f9da01dded48dcbe5fa13"
			+ "95559a2eb8160dd8615b22e900932a7bac035d6eaa57b1ec05f4cedf94c2d8054819918ba0b244669d6987678949825e"
			+ "a5dc3d9f582cab63e3a11d9f5dd0a95f6f0ce3ec010beb3a0aaa09cab69bb4208a6139d83fd2afb40c29caa1c439815a"
			+ "db67deae127d153f7724250620ba6b51";
	private static final String MAIN_BRANCH_PLAIN = "[{\"moduleCode\":\"moneyorder\",\"active\":true,\"defaultSelected\":true,"
			+ "\"environment\":\"TEST\",\"integrationKeys\":{\"address\":\"1 Main St\"}}]";

	@Test
	public void valueWrittenByPreviousReleaseDecrypts() throws Exception {
		EncryptionImpl e = encryption(KEY, LEGACY_KEY);
		assertEquals(MAIN_BRANCH_PLAIN, e.decrypt(MAIN_BRANCH_CBC_HEX));
		assertEquals("test helper must match the previous release", MAIN_BRANCH_CBC_HEX,
				legacyEncrypt(MAIN_BRANCH_PLAIN));
	}

	@Test
	public void legacyValueWithoutLegacyKeyFailsWithClearMessage() throws Exception {
		EncryptionImpl e = encryption(KEY, null);
		try {
			e.decrypt(legacyEncrypt(CONFIG));
			fail("legacy value decrypted without the legacy key");
		} catch (Exception expected) {
			assertTrue(expected.getMessage().contains(EncryptionImpl.LEGACY_KEY_ENV));
		}
	}

	@Test
	public void keyMustBe32Bytes() {
		String shortKey = Base64.getEncoder().encodeToString(LEGACY_KEY.getBytes(StandardCharsets.US_ASCII));
		try {
			encryption(shortKey, LEGACY_KEY);
			fail("16-byte key accepted");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().contains("32 bytes"));
		}
	}

	@Test
	public void productionProfileWithoutEnvironmentKeyFailsFast() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("mysql");
		// a development key in the properties must not be used by a production profile
		env.setProperty(EncryptionImpl.KEY_PROPERTY, KEY);
		EncryptionImpl e = new EncryptionImpl();
		e.setEnvironment(env);
		try {
			e.afterPropertiesSet();
			fail("started without " + EncryptionImpl.KEY_ENV);
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().contains(EncryptionImpl.KEY_ENV));
		}
	}

	@Test
	public void productionProfileUsesEnvironmentKeys() throws Exception {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("cloud");
		env.setProperty(EncryptionImpl.KEY_ENV, KEY);
		env.setProperty(EncryptionImpl.LEGACY_KEY_ENV, LEGACY_KEY);
		EncryptionImpl e = new EncryptionImpl();
		e.setSecretKey("ignored-in-production");
		e.setEnvironment(env);
		e.afterPropertiesSet();

		assertEquals(CONFIG, e.decrypt(e.encrypt(CONFIG)));
		assertEquals(CONFIG, e.decrypt(legacyEncrypt(CONFIG)));
	}

	@Test
	public void developmentProfileUsesPropertyKey() throws Exception {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("local");
		env.setProperty(EncryptionImpl.KEY_PROPERTY, KEY);
		EncryptionImpl e = new EncryptionImpl();
		e.setEnvironment(env);
		e.afterPropertiesSet();

		EncryptionImpl sameKey = encryption(KEY, null);
		assertEquals(CONFIG, sameKey.decrypt(e.encrypt(CONFIG)));
	}
}
