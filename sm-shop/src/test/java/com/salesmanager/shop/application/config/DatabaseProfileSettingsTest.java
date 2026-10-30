package com.salesmanager.shop.application.config;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;
import org.springframework.util.PropertyPlaceholderHelper;

/**
 * Hibernate's SQL echo is off in every shipped profile and can be switched on per
 * environment with DB_SHOW_SQL. The values are resolved the way the core XML placeholder
 * configurer resolves them: the file first, then system properties, then environment.
 */
@SuppressWarnings("deprecation")
public class DatabaseProfileSettingsTest {

	private static final String[] PROFILES = { "profiles/mysql/database.properties",
			"profiles/cloud/database.properties", "profiles/gcp/database.properties",
			"profiles/docker/database.properties", "profiles/dependency/database.properties",
			"profiles/local/database.properties" };

	@After
	public void clear() {
		System.clearProperty("DB_SHOW_SQL");
	}

	@Test
	public void showSqlIsOffInEveryProfile() throws IOException {
		for (String profile : PROFILES) {
			assertEquals(profile, "false", resolve(profile, "db.show.sql"));
		}
	}

	@Test
	public void defaultProfileShowSqlIsOff() throws IOException {
		// the default profile file (embedded H2) is listed in sm-shop/.gitignore, so a fresh
		// clone may not have it
		Assume.assumeTrue(new File("src/main/resources/database.properties").isFile());
		assertEquals("false", resolve("database.properties", "db.show.sql"));
	}

	@Test
	public void connectionTimeoutIsTwoSecondsInEveryProfile() throws IOException {
		for (String profile : PROFILES) {
			assertEquals(profile, "2000", resolve(profile, "db.connectionTimeout"));
		}
	}

	@Test
	public void showSqlCanBeEnabledFromOutside() throws IOException {
		System.setProperty("DB_SHOW_SQL", "true");
		for (String profile : PROFILES) {
			assertEquals(profile, "true", resolve(profile, "db.show.sql"));
		}
	}

	private static String resolve(String location, String key) throws IOException {
		Properties props = new Properties();
		// read the shipped files by path: test resources hold their own database.properties
		try (InputStream in = new FileInputStream("src/main/resources/" + location)) {
			props.load(in);
		}
		Resolver resolver = new Resolver();
		PropertyPlaceholderHelper helper = new PropertyPlaceholderHelper("${", "}", ":", true);
		return helper.replacePlaceholders(props.getProperty(key),
				placeholder -> resolver.resolve(placeholder, props)).trim();
	}

	/** exposes the configurer's own lookup order (SYSTEM_PROPERTIES_MODE_FALLBACK) */
	private static final class Resolver extends PropertyPlaceholderConfigurer {
		String resolve(String placeholder, Properties props) {
			return resolvePlaceholder(placeholder, props, SYSTEM_PROPERTIES_MODE_FALLBACK);
		}
	}
}
