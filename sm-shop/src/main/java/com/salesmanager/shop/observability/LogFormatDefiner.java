package com.salesmanager.shop.observability;

import ch.qos.logback.core.PropertyDefinerBase;

/**
 * Chooses the console appender in logback-spring.xml from LOG_FORMAT (system property
 * first, then environment). "json" selects one JSON object per line for log shipping;
 * any other value, or none, selects the readable Spring Boot pattern. Mapping unknown
 * values to "text" means a typo can never leave the application without logs.
 */
public class LogFormatDefiner extends PropertyDefinerBase {

	public static final String JSON = "json";
	public static final String TEXT = "text";

	@Override
	public String getPropertyValue() {
		String value = System.getProperty("LOG_FORMAT");
		if (value == null) {
			value = System.getenv("LOG_FORMAT");
		}
		return format(value);
	}

	static String format(String value) {
		return value != null && JSON.equalsIgnoreCase(value.trim()) ? JSON : TEXT;
	}
}
