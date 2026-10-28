package com.salesmanager.core.business.modules.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import java.util.Properties;

import org.junit.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

public class DefaultEmailSenderImplTest {

	private EmailConfig config(String host, String user) {
		EmailConfig c = new EmailConfig();
		c.setHost(host);
		c.setPort("587");
		c.setProtocol("smtp");
		c.setUsername(user);
		c.setPassword("pw-" + user);
		c.setSmtpAuth(true);
		return c;
	}

	@Test
	public void storeConfigBuildsDedicatedSenderAndLeavesSharedOneUntouched() {
		DefaultEmailSenderImpl sender = new DefaultEmailSenderImpl();
		JavaMailSenderImpl shared = new JavaMailSenderImpl();
		shared.setHost("default.host");
		sender.setMailSender(shared);

		JavaMailSenderImpl a = sender.buildSender(config("a.host", "a"));
		JavaMailSenderImpl b = sender.buildSender(config("b.host", "b"));

		assertNotSame(a, b);
		assertEquals("a.host", a.getHost());
		assertEquals("b", b.getUsername());
		assertEquals("default.host", shared.getHost());
		assertNull(shared.getUsername());
		Properties p = a.getJavaMailProperties();
		assertEquals("3000", p.getProperty("mail.smtp.connectiontimeout"));
		assertEquals("10000", p.getProperty("mail.smtp.timeout"));
		assertEquals("10000", p.getProperty("mail.smtp.writetimeout"));
		assertEquals("true", p.getProperty("mail.smtp.auth"));
	}

	@Test
	public void defaultSenderGetsTimeoutsWithoutOverridingExplicitValues() {
		DefaultEmailSenderImpl sender = new DefaultEmailSenderImpl();
		JavaMailSenderImpl shared = new JavaMailSenderImpl();
		shared.getJavaMailProperties().setProperty("mail.smtp.timeout", "1234");
		sender.setMailSender(shared);

		sender.applyTimeoutsToDefaultSender();

		assertEquals("1234", shared.getJavaMailProperties().getProperty("mail.smtp.timeout"));
		assertEquals("3000", shared.getJavaMailProperties().getProperty("mail.smtp.connectiontimeout"));
	}

	@Test
	public void configIsScopedToCallingThread() throws Exception {
		DefaultEmailSenderImpl sender = new DefaultEmailSenderImpl();
		sender.setEmailConfig(config("a.host", "a"));
		EmailConfig[] seen = new EmailConfig[1];
		Thread t = new Thread(() -> seen[0] = sender.getEmailConfig());
		t.start();
		t.join();
		assertNull(seen[0]);
		assertEquals("a.host", sender.getEmailConfig().getHost());
	}
}
