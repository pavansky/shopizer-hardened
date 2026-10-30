package com.salesmanager.test.shop.integration.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.salesmanager.core.business.constants.Constants;
import com.salesmanager.core.model.customer.CustomerGender;
import com.salesmanager.shop.model.customer.PersistableCustomer;
import com.salesmanager.shop.model.customer.address.Address;
import com.salesmanager.shop.model.security.PersistableGroup;
import com.salesmanager.shop.model.user.PersistableUser;
import com.salesmanager.shop.model.user.ReadableUser;
import com.salesmanager.shop.model.user.UserPassword;
import com.salesmanager.shop.store.security.AuthenticationRequest;
import com.salesmanager.test.shop.common.ServicesTestSupport;

/**
 * Login failures do not reveal whether an account exists, a deactivated admin
 * loses access on the next request, and a password change ends older sessions.
 */
class AdminLoginAndSessionIntegrationTest extends ServicesTestSupport {

	private ResponseEntity<String> login(String path, String user, String password) {
		return testRestTemplate.postForEntity(path, new HttpEntity<>(new AuthenticationRequest(user, password)),
				String.class);
	}

	@Test
	void adminLoginUnknownUserAndWrongPasswordGetTheSameResponse() {
		ResponseEntity<String> unknown = login("/api/v1/private/login", "nobody-at-all@shopizer.com", "password");
		ResponseEntity<String> wrong = login("/api/v1/private/login", "admin@shopizer.com", "not-the-password");
		assertEquals(HttpStatus.UNAUTHORIZED, wrong.getStatusCode());
		assertEquals(wrong.getStatusCode(), unknown.getStatusCode());
		assertEquals(wrong.getBody(), unknown.getBody());
	}

	@Test
	void customerLoginUnknownUserAndWrongPasswordGetTheSameResponse() {
		PersistableCustomer customer = new PersistableCustomer();
		customer.setEmailAddress("enumeration-check@test.com");
		customer.setPassword("clear123");
		customer.setGender(CustomerGender.M.name());
		customer.setLanguage("en");
		Address billing = new Address();
		billing.setFirstName("enum");
		billing.setLastName("check");
		billing.setCountry("BE");
		customer.setBilling(billing);
		customer.setStoreCode(Constants.DEFAULT_STORE);
		testRestTemplate.postForEntity("/api/v1/customer/register", new HttpEntity<>(customer), String.class);

		ResponseEntity<String> unknown = login("/api/v1/customer/login", "nobody-at-all@test.com", "clear123");
		ResponseEntity<String> wrong = login("/api/v1/customer/login", "enumeration-check@test.com", "wrong");
		assertEquals(HttpStatus.UNAUTHORIZED, wrong.getStatusCode());
		assertEquals(wrong.getStatusCode(), unknown.getStatusCode());
		assertEquals(wrong.getBody(), unknown.getBody());
	}

	private ReadableUser createAdmin(String userName, String password) {
		PersistableUser user = new PersistableUser();
		user.setDefaultLanguage("en");
		user.setEmailAddress(userName);
		user.setFirstName("Staff");
		user.setLastName("Member");
		user.setUserName(userName);
		user.setPassword(password);
		user.setRepeatPassword(password);
		user.setActive(true);
		PersistableGroup group = new PersistableGroup();
		group.setName("ADMIN");
		user.getGroups().add(group);
		ResponseEntity<ReadableUser> created = testRestTemplate.exchange("/api/v1/private/user/", HttpMethod.POST,
				new HttpEntity<>(user, getHeader()), ReadableUser.class);
		assertEquals(HttpStatus.OK, created.getStatusCode(), created.toString());
		return created.getBody();
	}

	private HttpHeaders bearer(String user, String password) {
		HttpHeaders headers = getHeader(user, password);
		assertNotNull(headers.getFirst("Authorization"));
		return headers;
	}

	private HttpStatus profile(HttpHeaders headers) {
		return testRestTemplate.exchange("/api/v1/private/user/profile", HttpMethod.GET, new HttpEntity<>(headers),
				String.class).getStatusCode();
	}

	private HttpStatus refresh(HttpHeaders headers) {
		return testRestTemplate.exchange("/api/v1/auth/refresh", HttpMethod.GET, new HttpEntity<>(headers),
				String.class).getStatusCode();
	}

	@Test
	void deactivatedAdminLosesAccessOnTheNextRequest() {
		ReadableUser staff = createAdmin("deactivate-me@test.com", "Password1");
		HttpHeaders staffToken = bearer("deactivate-me@test.com", "Password1");
		assertEquals(HttpStatus.OK, profile(staffToken));

		PersistableUser disable = new PersistableUser();
		disable.setUserName("deactivate-me@test.com");
		disable.setEmailAddress("deactivate-me@test.com");
		disable.setActive(false);
		ResponseEntity<String> patched = testRestTemplate.exchange(
				"/api/v1/private/user/" + staff.getId() + "/enabled", HttpMethod.PATCH,
				new HttpEntity<>(disable, getHeader()), String.class);
		assertEquals(HttpStatus.OK, patched.getStatusCode(), patched.toString());

		assertEquals(HttpStatus.UNAUTHORIZED, profile(staffToken));
		assertEquals(HttpStatus.UNAUTHORIZED, refresh(staffToken));
		assertEquals(HttpStatus.UNAUTHORIZED,
				login("/api/v1/private/login", "deactivate-me@test.com", "Password1").getStatusCode());
	}

	@Test
	void adminApiIsStatelessSoNoCookieOutlivesADeactivation() {
		ReadableUser staff = createAdmin("cookie-check@test.com", "Password1");
		ResponseEntity<String> loggedIn = login("/api/v1/private/login", "cookie-check@test.com", "Password1");
		assertEquals(HttpStatus.OK, loggedIn.getStatusCode());
		assertNull(loggedIn.getHeaders().get(HttpHeaders.SET_COOKIE), loggedIn.getHeaders().toString());

		HttpHeaders staffToken = bearer("cookie-check@test.com", "Password1");
		ResponseEntity<String> withToken = testRestTemplate.exchange("/api/v1/private/category", HttpMethod.POST,
				new HttpEntity<>(category("cookie-check-1"), staffToken), String.class);
		assertEquals(HttpStatus.CREATED, withToken.getStatusCode(), withToken.toString());
		List<String> cookies = withToken.getHeaders().get(HttpHeaders.SET_COOKIE);
		assertNull(cookies, String.valueOf(cookies));

		PersistableUser disable = new PersistableUser();
		disable.setUserName("cookie-check@test.com");
		disable.setEmailAddress("cookie-check@test.com");
		disable.setActive(false);
		assertEquals(HttpStatus.OK, testRestTemplate.exchange("/api/v1/private/user/" + staff.getId() + "/enabled",
				HttpMethod.PATCH, new HttpEntity<>(disable, getHeader()), String.class).getStatusCode());

		// a session cookie, if one were ever issued, must not authenticate either
		HttpHeaders cookieOnly = new HttpHeaders();
		cookieOnly.setContentType(MediaType.APPLICATION_JSON);
		cookieOnly.add(HttpHeaders.COOKIE, "JSESSIONID=" + UUID.randomUUID());
		assertEquals(HttpStatus.UNAUTHORIZED, testRestTemplate.exchange("/api/v1/private/category", HttpMethod.POST,
				new HttpEntity<>(category("cookie-check-2"), cookieOnly), String.class).getStatusCode());
		assertEquals(HttpStatus.UNAUTHORIZED, testRestTemplate.exchange("/api/v1/private/category", HttpMethod.POST,
				new HttpEntity<>(category("cookie-check-3"), staffToken), String.class).getStatusCode());
	}

	@Test
	void passwordChangeEndsOlderSessions() throws Exception {
		ReadableUser staff = createAdmin("rotate-me@test.com", "Password1");
		HttpHeaders oldToken = bearer("rotate-me@test.com", "Password1");
		assertEquals(HttpStatus.OK, profile(oldToken));

		// token issue times have one second precision
		Thread.sleep(1100);

		UserPassword change = new UserPassword();
		change.setPassword("Password1");
		change.setChangePassword("Password2");
		HttpHeaders json = new HttpHeaders();
		json.putAll(oldToken);
		json.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
		ResponseEntity<String> changed = testRestTemplate.exchange("/api/v1/private/user/" + staff.getId() + "/password",
				HttpMethod.PATCH, new HttpEntity<>(change, json), String.class);
		assertEquals(HttpStatus.OK, changed.getStatusCode(), changed.toString());

		assertEquals(HttpStatus.UNAUTHORIZED, profile(oldToken));
		// the admin UI refreshes on every 401: the old token must not be renewed either
		assertEquals(HttpStatus.UNAUTHORIZED, refresh(oldToken));
		HttpHeaders newToken = bearer("rotate-me@test.com", "Password2");
		assertEquals(HttpStatus.OK, profile(newToken));
		assertEquals(HttpStatus.OK, refresh(newToken));
	}
}
