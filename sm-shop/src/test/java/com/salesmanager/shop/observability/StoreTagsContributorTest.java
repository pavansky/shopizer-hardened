package com.salesmanager.shop.observability;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import com.salesmanager.core.model.merchant.MerchantStore;

import io.micrometer.core.instrument.Tag;

public class StoreTagsContributorTest {

	private final KnownStores knownStores = new KnownStores();
	private final StoreTagsContributor contributor = new StoreTagsContributor(knownStores);

	@SuppressWarnings("unused")
	static class Handlers {
		public void storeScoped(MerchantStore store) { }
		public void notStoreScoped(String code) { }
	}

	@Before
	public void stores() {
		knownStores.setCodes(new HashSet<>(Arrays.asList("DEFAULT", "B2B")));
	}

	@Test
	public void knownStoreParameterIsTheTag() throws Exception {
		assertEquals("B2B", tag(request("B2B"), handler("storeScoped")));
	}

	@Test
	public void unknownStoreParameterIsNoneSoClientsCannotCreateSeries() throws Exception {
		assertEquals("none", tag(request("random-" + System.nanoTime()), handler("storeScoped")));
	}

	@Test
	public void storeScopedEndpointWithoutParameterIsTheDefaultStore() throws Exception {
		assertEquals("DEFAULT", tag(request(null), handler("storeScoped")));
	}

	@Test
	public void endpointWithoutStoreIsNone() throws Exception {
		assertEquals("none", tag(request(null), handler("notStoreScoped")));
		assertEquals("none", tag(request(null), null));
	}

	private String tag(MockHttpServletRequest request, Object handler) {
		Tag tag = contributor.getTags(request, new MockHttpServletResponse(), handler, null).iterator().next();
		assertEquals("store", tag.getKey());
		return tag.getValue();
	}

	private static MockHttpServletRequest request(String store) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/category");
		if (store != null) {
			request.setParameter("store", store);
		}
		return request;
	}

	private static HandlerMethod handler(String name) throws Exception {
		for (java.lang.reflect.Method m : Handlers.class.getMethods()) {
			if (m.getName().equals(name)) {
				return new HandlerMethod(new Handlers(), m);
			}
		}
		throw new IllegalArgumentException(name);
	}
}
