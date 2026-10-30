package com.salesmanager.shop.utils;

import java.util.Locale;
import java.util.regex.Pattern;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;

/**
 * Cleans merchant-authored HTML (product, content, category and manufacturer
 * descriptions) before it is stored. The storefront renders these fields as
 * HTML, so anything a staff account saves would otherwise run in every
 * shopper's browser.
 *
 * Allowed: text formatting, headings, paragraphs, lists, block quotes, tables,
 * inline styles from a safe CSS subset, links (http, https, mailto; rel=nofollow
 * is added) and images (http, https, relative, and inline base64 PNG, JPEG, GIF
 * or WebP, which is what the admin editor inserts for pasted and uploaded
 * images). Everything else, including script, iframe, form, event handler
 * attributes, javascript: URLs and data:image/svg+xml, is removed.
 */
public final class RichTextSanitizer {

	/** Raster images only: SVG can carry script. */
	private static final Pattern DATA_IMAGE =
			Pattern.compile("data:image/(png|jpeg|gif|webp);base64,[A-Za-z0-9+/=\\s]+");

	private static final Pattern INTEGER = Pattern.compile("[0-9]+");

	/**
	 * Replaces Sanitizers.IMAGES, which allows only http and https sources. The
	 * protocol filter still applies; the predicate narrows data: to raster images.
	 */
	private static final PolicyFactory IMAGES = new HtmlPolicyBuilder()
			.allowUrlProtocols("http", "https", "data")
			.allowElements("img")
			.allowAttributes("alt").onElements("img")
			.allowAttributes("src").matching(RichTextSanitizer::isAllowedImageSource).onElements("img")
			.allowAttributes("border", "height", "width").matching(INTEGER).onElements("img")
			.toFactory();

	private static final PolicyFactory POLICY = Sanitizers.FORMATTING
			.and(Sanitizers.BLOCKS)
			.and(Sanitizers.TABLES)
			.and(Sanitizers.STYLES)
			.and(Sanitizers.LINKS)
			.and(IMAGES)
			.and(new HtmlPolicyBuilder()
					.allowElements("hr", "span", "div")
					.allowAttributes("class").onElements("p", "span", "div", "table", "td", "th", "tr", "ul", "ol", "li", "img", "a")
					.toFactory());

	private RichTextSanitizer() {
	}

	static boolean isAllowedImageSource(String value) {
		String v = value.trim();
		if (v.toLowerCase(Locale.ROOT).startsWith("data:")) {
			return DATA_IMAGE.matcher(v).matches();
		}
		return true;
	}

	/** Returns the cleaned HTML, or null for null input. */
	public static String sanitize(String html) {
		if (html == null) {
			return null;
		}
		return POLICY.sanitize(html);
	}
}
