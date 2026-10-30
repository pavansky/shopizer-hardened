package com.salesmanager.shop.store.security.abuse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * Resolves the address of the client that sent a request.
 *
 * By default this is the TCP peer address and every forwarding header is
 * ignored, because any client can send X-Forwarded-For. Only when the peer is
 * one of the configured trusted proxies (shopizer.security.trusted-proxies,
 * comma separated addresses or CIDR ranges) is X-Forwarded-For read, from the
 * right: the first entry that is not itself a trusted proxy is the client.
 */
public class ClientIpResolver {

	static final String FORWARDED_FOR = "X-Forwarded-For";

	/** IPv4 or IPv6 literal, optionally with a prefix length. Never a host name, which would trigger DNS. */
	private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F:.]+(/[0-9]{1,3})?");

	private final List<IpAddressMatcher> trustedProxies;

	public ClientIpResolver(String trustedProxiesProperty) {
		List<IpAddressMatcher> matchers = new ArrayList<>();
		if (StringUtils.isNotBlank(trustedProxiesProperty)) {
			for (String entry : trustedProxiesProperty.split(",")) {
				String value = entry.trim();
				if (value.isEmpty()) {
					continue;
				}
				if (!IP_LITERAL.matcher(value).matches()) {
					throw new IllegalArgumentException("shopizer.security.trusted-proxies entry is not an address or CIDR: " + value);
				}
				matchers.add(new IpAddressMatcher(value));
			}
		}
		this.trustedProxies = Collections.unmodifiableList(matchers);
	}

	public String resolve(HttpServletRequest request) {
		String peer = request.getRemoteAddr();
		if (trustedProxies.isEmpty() || !isTrusted(peer)) {
			return peer;
		}
		String header = request.getHeader(FORWARDED_FOR);
		if (StringUtils.isBlank(header)) {
			return peer;
		}
		String[] hops = header.split(",");
		String leftmost = peer;
		for (int i = hops.length - 1; i >= 0; i--) {
			String hop = hops[i].trim();
			if (!IP_LITERAL.matcher(hop).matches() || hop.contains("/")) {
				// garbage in the chain: stop at the last address we could trust
				return leftmost;
			}
			if (!isTrusted(hop)) {
				return hop;
			}
			leftmost = hop;
		}
		return leftmost;
	}

	/** True when the request reached us through a trusted proxy that reports the original scheme as https. */
	public boolean isForwardedHttps(HttpServletRequest request) {
		return !trustedProxies.isEmpty() && isTrusted(request.getRemoteAddr())
				&& "https".equalsIgnoreCase(StringUtils.trimToEmpty(request.getHeader("X-Forwarded-Proto")));
	}

	private boolean isTrusted(String address) {
		if (address == null || !IP_LITERAL.matcher(address).matches()) {
			return false;
		}
		for (IpAddressMatcher m : trustedProxies) {
			try {
				if (m.matches(address)) {
					return true;
				}
			} catch (IllegalArgumentException e) {
				// unparsable address: not trusted
			}
		}
		return false;
	}
}
