package com.salesmanager.core.business.modules.utils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.salesmanager.core.modules.utils.Encryption;

/**
 * Encrypts stored module configuration (payment and shipping gateway credentials).
 *
 * New values are AES-256-GCM with a random 12-byte IV per value, stored as
 * {@code v2:base64(iv || ciphertext || tag)}. Values without the prefix were written by
 * the previous AES-128-CBC scheme with a fixed IV. They are still decrypted with the
 * legacy key, and callers that save configuration re-encrypt them as v2 on the next write.
 *
 * Key sources:
 * <ul>
 * <li>Production profiles (mysql, gcp, cloud, aws) read SHOPIZER_ENCRYPTION_KEY (base64 of
 * 32 bytes) from the environment and refuse to start without it. The legacy CBC key, needed
 * only to read values written before v2, comes from SHOPIZER_LEGACY_SECRET_KEY.</li>
 * <li>Other profiles use the {@code encryptionKey} property, which falls back to a labelled
 * development key, and the {@code secretKey} property for legacy values.</li>
 * </ul>
 */
public final class EncryptionImpl implements Encryption, EnvironmentAware, InitializingBean {

	private static final Logger LOGGER = LoggerFactory.getLogger(EncryptionImpl.class);

	public static final String KEY_ENV = "SHOPIZER_ENCRYPTION_KEY";
	public static final String LEGACY_KEY_ENV = "SHOPIZER_LEGACY_SECRET_KEY";
	public static final String KEY_PROPERTY = "encryptionKey";
	static final String[] PRODUCTION_PROFILES = { "mysql", "gcp", "cloud", "aws" };

	public static final String V2_PREFIX = "v2:";
	private static final String GCM_SPEC = "AES/GCM/NoPadding";
	private static final int GCM_IV_BYTES = 12;
	private static final int GCM_TAG_BITS = 128;
	private static final int KEY_BYTES = 32;

	private static final String LEGACY_IV = "fedcba9876543210";
	private static final String LEGACY_SPEC = "AES/CBC/PKCS5Padding";

	private final SecureRandom random = new SecureRandom();

	/** Legacy AES-CBC key, used only to decrypt values written before v2. */
	private String secretKey;
	/** Base64 encoded 32-byte AES key for v2 values. */
	private String encryptionKey;
	private Environment environment;
	private volatile SecretKeySpec gcmKey;

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void afterPropertiesSet() {
		boolean production = environment != null && environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILES));
		if (production) {
			// read the environment directly so a key from a non-production properties file never applies
			encryptionKey = environment.getProperty(KEY_ENV);
			secretKey = environment.getProperty(LEGACY_KEY_ENV);
			if (StringUtils.isBlank(encryptionKey)) {
				throw new IllegalStateException(KEY_ENV + " is not set. The production profiles "
						+ Arrays.toString(PRODUCTION_PROFILES) + " need a base64 encoded 32-byte key"
						+ " (for example from: openssl rand -base64 32) to encrypt payment and shipping credentials.");
			}
		} else if (StringUtils.isBlank(encryptionKey) && environment != null) {
			encryptionKey = environment.getProperty(KEY_PROPERTY);
			if (StringUtils.isBlank(environment.getProperty(KEY_ENV))) {
				LOGGER.warn("Using the development encryption key; set {} before storing real credentials", KEY_ENV);
			}
		}
		gcmKey = new SecretKeySpec(decodeKey(encryptionKey), "AES");
	}

	private static byte[] decodeKey(String base64) {
		if (StringUtils.isBlank(base64)) {
			throw new IllegalStateException("No encryption key configured; set " + KEY_ENV
					+ " to a base64 encoded 32-byte key");
		}
		byte[] key;
		try {
			key = Base64.getDecoder().decode(base64.trim());
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("The encryption key (" + KEY_ENV + ") is not valid base64");
		}
		if (key.length != KEY_BYTES) {
			throw new IllegalStateException("The encryption key (" + KEY_ENV + ") must decode to " + KEY_BYTES
					+ " bytes for AES-256, got " + key.length);
		}
		return key;
	}

	private SecretKeySpec key() {
		if (gcmKey == null) {
			afterPropertiesSet();
		}
		return gcmKey;
	}

	@Override
	public String encrypt(String value) throws Exception {
		if (value == null) {
			throw new Exception("Nothing to encrypt");
		}
		byte[] iv = new byte[GCM_IV_BYTES];
		random.nextBytes(iv);
		Cipher cipher = Cipher.getInstance(GCM_SPEC);
		cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
		byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
		byte[] out = new byte[iv.length + encrypted.length];
		System.arraycopy(iv, 0, out, 0, iv.length);
		System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
		return V2_PREFIX + Base64.getEncoder().encodeToString(out);
	}

	@Override
	public String decrypt(String value) throws Exception {
		if (StringUtils.isBlank(value)) {
			throw new Exception("Nothing to decrypt");
		}
		if (value.startsWith(V2_PREFIX)) {
			byte[] in = Base64.getDecoder().decode(value.substring(V2_PREFIX.length()));
			if (in.length <= GCM_IV_BYTES) {
				throw new Exception("Encrypted value is too short");
			}
			Cipher cipher = Cipher.getInstance(GCM_SPEC);
			cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, in, 0, GCM_IV_BYTES));
			byte[] plain = cipher.doFinal(in, GCM_IV_BYTES, in.length - GCM_IV_BYTES);
			return new String(plain, StandardCharsets.UTF_8);
		}
		return decryptLegacy(value);
	}

	/** AES-CBC with the fixed IV and hex encoding, as written before v2. */
	private String decryptLegacy(String value) throws Exception {
		if (StringUtils.isBlank(secretKey)) {
			throw new Exception("Value was encrypted with the legacy key, which is not configured; set "
					+ LEGACY_KEY_ENV + " to read it");
		}
		Cipher cipher = Cipher.getInstance(LEGACY_SPEC);
		SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "AES");
		cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(LEGACY_IV.getBytes(StandardCharsets.UTF_8)));
		return new String(cipher.doFinal(hexToBytes(value)), StandardCharsets.UTF_8);
	}

	private static byte[] hexToBytes(String str) throws Exception {
		if (str.length() % 2 != 0) {
			throw new Exception("Legacy encrypted value is not hex encoded");
		}
		int len = str.length() / 2;
		byte[] buffer = new byte[len];
		for (int i = 0; i < len; i++) {
			buffer[i] = (byte) Integer.parseInt(str.substring(i * 2, i * 2 + 2), 16);
		}
		return buffer;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	public void setEncryptionKey(String encryptionKey) {
		this.encryptionKey = encryptionKey;
	}

}
