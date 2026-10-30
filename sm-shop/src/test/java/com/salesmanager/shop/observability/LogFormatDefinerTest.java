package com.salesmanager.shop.observability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.net.URL;

import org.junit.After;
import org.junit.Test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.ConsoleAppender;
import net.logstash.logback.encoder.LogstashEncoder;

public class LogFormatDefinerTest {

	@After
	public void clearProperty() {
		System.clearProperty("LOG_FORMAT");
	}

	@Test
	public void onlyJsonSelectsJsonEverythingElseIsText() {
		assertEquals("json", LogFormatDefiner.format("json"));
		assertEquals("json", LogFormatDefiner.format(" JSON "));
		assertEquals("text", LogFormatDefiner.format(null));
		assertEquals("text", LogFormatDefiner.format(""));
		assertEquals("text", LogFormatDefiner.format("text"));
		assertEquals("text", LogFormatDefiner.format("jsn"));
	}

	@Test
	public void logbackConfigUsesTheJsonEncoderWhenAsked() throws Exception {
		System.setProperty("LOG_FORMAT", "json");
		Logger root = configure();
		ConsoleAppender<?> json = (ConsoleAppender<?>) root.getAppender("json");
		assertNotNull(json);
		assertEquals(LogstashEncoder.class, json.getEncoder().getClass());
		assertNull(root.getAppender("text"));
	}

	@Test
	public void logbackConfigFallsBackToTextForUnknownValues() throws Exception {
		System.setProperty("LOG_FORMAT", "plain");
		Logger root = configure();
		assertNotNull(root.getAppender("text"));
		assertNull(root.getAppender("json"));
	}

	private static Logger configure() throws Exception {
		LoggerContext context = new LoggerContext();
		JoranConfigurator configurator = new JoranConfigurator();
		configurator.setContext(context);
		URL config = LogFormatDefinerTest.class.getClassLoader().getResource("logback-spring.xml");
		configurator.doConfigure(config);
		return context.getLogger(Logger.ROOT_LOGGER_NAME);
	}
}
