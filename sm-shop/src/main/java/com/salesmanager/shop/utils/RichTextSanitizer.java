package com.salesmanager.shop.utils;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;

/**
 * Cleans merchant-authored HTML (product and content descriptions) before it
 * is stored. The storefront renders these fields as HTML, so anything a staff
 * account saves would otherwise run in every shopper's browser.
 *
 * Allowed: text formatting, headings, paragraphs, lists, block quotes, tables,
 * inline styles from a safe CSS subset, links (http, https, mailto; rel=nofollow
 * is added) and images (http, https). Everything else, including script, iframe,
 * form, event handler attributes and javascript: URLs, is removed.
 */
public final class RichTextSanitizer {

	private static final PolicyFactory POLICY = Sanitizers.FORMATTING
			.and(Sanitizers.BLOCKS)
			.and(Sanitizers.TABLES)
			.and(Sanitizers.STYLES)
			.and(Sanitizers.LINKS)
			.and(Sanitizers.IMAGES)
			.and(new HtmlPolicyBuilder()
					.allowElements("hr", "span", "div")
					.allowAttributes("class").onElements("p", "span", "div", "table", "td", "th", "tr", "ul", "ol", "li", "img", "a")
					.toFactory());

	private RichTextSanitizer() {
	}

	/** Returns the cleaned HTML, or null for null input. */
	public static String sanitize(String html) {
		if (html == null) {
			return null;
		}
		return POLICY.sanitize(html);
	}
}
