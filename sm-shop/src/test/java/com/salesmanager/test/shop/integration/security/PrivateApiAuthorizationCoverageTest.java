package com.salesmanager.test.shop.integration.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.servlet.Filter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.ConfigAttribute;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.FilterInvocation;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.FilterSecurityInterceptor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.salesmanager.shop.model.catalog.category.PersistableCategory;
import com.salesmanager.shop.model.security.PersistableGroup;
import com.salesmanager.shop.model.user.PersistableUser;
import com.salesmanager.test.shop.common.ServicesTestSupport;

/**
 * Every /private endpoint must be matched by a rule that names what it needs.
 * The admin chain ends with denyAll, so an endpoint added without a rule is
 * unreachable at run time and this test names it.
 */
class PrivateApiAuthorizationCoverageTest extends ServicesTestSupport {

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	private static final String GENERIC = "[hasRole('ROLE_AUTH')]";

	private static final List<String> ALL_METHODS = Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE");

	/** The access expression the running security configuration applies to a request. */
	private String accessFor(String method, String path) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setServletPath(path);
		for (SecurityFilterChain chain : springSecurityFilterChain.getFilterChains()) {
			if (!chain.matches(request)) {
				continue;
			}
			for (Filter f : chain.getFilters()) {
				if (f instanceof FilterSecurityInterceptor) {
					Collection<ConfigAttribute> attributes = ((FilterSecurityInterceptor) f).getSecurityMetadataSource()
							.getAttributes(new FilterInvocation(request, new MockHttpServletResponse(), new MockFilterChain()));
					return String.valueOf(attributes);
				}
			}
			return "no authorization filter";
		}
		return "no chain";
	}

	@Test
	void everyPrivateEndpointHasAnExplicitRule() {
		Set<String> uncovered = new TreeSet<>();
		int checked = 0;
		for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
			RequestMappingInfo info = e.getKey();
			List<String> methods = new ArrayList<>();
			for (RequestMethod m : info.getMethodsCondition().getMethods()) {
				methods.add(m.name());
			}
			if (methods.isEmpty()) {
				methods = ALL_METHODS;
			}
			for (String pattern : info.getPatternValues()) {
				if (!pattern.contains("/private/")) {
					continue;
				}
				String sample = pattern.replaceAll("\\{[^}]+\\}", "1");
				for (String method : methods) {
					checked++;
					String access = accessFor(method, sample);
					boolean login = sample.endsWith("/private/login") || sample.endsWith("/private/refresh");
					// [hasRole('ROLE_AUTH')] is the old "any logged in user" catch-all;
					// rules deliberately open to every staff member say access("hasRole('AUTH')")
					if (access.contains("denyAll") || access.startsWith("no ")
							|| (!login && access.contains("permitAll"))
							|| access.equals(GENERIC)) {
						uncovered.add(method + " " + pattern + " -> " + access + " (" + e.getValue().getShortLogMessage() + ")");
					}
				}
			}
		}
		assertTrue(checked > 150, "expected the private endpoints to be found, checked " + checked);
		assertTrue(uncovered.isEmpty(), "private endpoints without an explicit access rule:\n" + String.join("\n", uncovered));
	}

	@Test
	void sensitiveEndpointsNeedMoreThanALogin() {
		String generic = GENERIC;
		assertNotEquals(generic, accessFor("POST", "/api/v1/private/orders/1/refund"));
		assertNotEquals(generic, accessFor("POST", "/api/v1/private/modules/payment"));
		assertNotEquals(generic, accessFor("POST", "/api/v1/private/user/"));
		assertNotEquals(generic, accessFor("PUT", "/api/v1/private/store/DEFAULT"));
		assertNotEquals(generic, accessFor("POST", "/api/v2/private/product"));
		assertEquals("[denyAll]", accessFor("POST", "/api/v1/private/not-an-endpoint"));
	}

	private HttpHeaders catalogOnlyUser() {
		PersistableUser user = new PersistableUser();
		user.setDefaultLanguage("en");
		user.setEmailAddress("catalog-only@test.com");
		user.setFirstName("Catalog");
		user.setLastName("Editor");
		user.setUserName("catalog-only@test.com");
		user.setPassword("Password1");
		user.setRepeatPassword("Password1");
		user.setActive(true);
		PersistableGroup group = new PersistableGroup();
		group.setName("ADMIN_CATALOGUE");
		user.getGroups().add(group);
		ResponseEntity<String> created = testRestTemplate.exchange("/api/v1/private/user/", HttpMethod.POST,
				new HttpEntity<>(user, getHeader()), String.class);
		assertEquals(HttpStatus.OK, created.getStatusCode(), created.toString());
		return getHeader("catalog-only@test.com", "Password1");
	}

	private HttpStatus call(HttpMethod method, String path, Object body, HttpHeaders headers) {
		return testRestTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class).getStatusCode();
	}

	@Test
	void catalogEditorCanEditTheCatalogButNotOrdersPaymentsOrUsers() {
		HttpHeaders catalog = catalogOnlyUser();

		assertEquals(HttpStatus.OK, call(HttpMethod.GET, "/api/v1/private/user/profile", null, catalog));
		PersistableCategory category = category("rbac-category");
		assertEquals(HttpStatus.CREATED, call(HttpMethod.POST, "/api/v1/private/category", category, catalog));

		assertEquals(HttpStatus.FORBIDDEN, call(HttpMethod.GET, "/api/v1/private/orders", null, catalog));
		assertEquals(HttpStatus.FORBIDDEN, call(HttpMethod.POST, "/api/v1/private/orders/1/refund", "{}", catalog));
		assertEquals(HttpStatus.FORBIDDEN, call(HttpMethod.POST, "/api/v1/private/modules/payment", "{}", catalog));
		assertEquals(HttpStatus.FORBIDDEN, call(HttpMethod.GET, "/api/v1/private/users", null, catalog));
		assertEquals(HttpStatus.FORBIDDEN, call(HttpMethod.PUT, "/api/v1/private/store/DEFAULT", "{}", catalog));
		assertEquals(HttpStatus.FORBIDDEN, call(HttpMethod.GET, "/api/v1/private/customers", null, catalog));

		// the password rule is self service only: even with the right current password a
		// staff member cannot change the superadmin's (user 1) password
		HttpHeaders json = new HttpHeaders();
		json.putAll(catalog);
		json.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
		com.salesmanager.shop.model.user.UserPassword takeover = new com.salesmanager.shop.model.user.UserPassword();
		takeover.setPassword("password");
		takeover.setChangePassword("taken-over");
		assertEquals(HttpStatus.FORBIDDEN, call(HttpMethod.PATCH, "/api/v1/private/user/1/password", takeover, json));
		assertEquals("[@selfUser.isSelf(authentication, #id) or hasAuthority('SUPERADMIN')]",
				accessFor("PATCH", "/api/v1/private/user/1/password"));

		// the demo superadmin keeps full access
		HttpHeaders superadmin = getHeader();
		assertEquals(HttpStatus.OK, call(HttpMethod.GET, "/api/v1/private/orders", null, superadmin));
		assertEquals(HttpStatus.OK, call(HttpMethod.GET, "/api/v1/private/users", null, superadmin));
		assertEquals(HttpStatus.OK, call(HttpMethod.GET, "/api/v1/private/modules/payment", null, superadmin));
	}
}
