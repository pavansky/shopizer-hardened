package com.salesmanager.test.shop.integration.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import com.salesmanager.core.business.constants.Constants;
import com.salesmanager.core.business.services.merchant.MerchantStoreService;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.business.services.system.MerchantConfigurationService;
import com.salesmanager.core.business.services.system.audit.ConfigurationAuditService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.model.system.MerchantConfiguration;
import com.salesmanager.core.model.system.audit.ConfigurationAuditEntry;
import com.salesmanager.shop.application.ShopApplication;
import com.salesmanager.shop.model.references.PersistableAddress;
import com.salesmanager.shop.model.security.PersistableGroup;
import com.salesmanager.shop.model.store.PersistableMerchantStore;
import com.salesmanager.shop.model.system.IntegrationModuleConfiguration;
import com.salesmanager.shop.model.user.PersistableUser;
import com.salesmanager.test.shop.common.ServicesTestSupport;

/**
 * Payment module configuration over HTTP: secrets are masked on read, a masked value
 * sent back keeps the stored secret, every change is audited, and only store
 * administrators of the right store get in.
 */
@SpringBootTest(classes = ShopApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@RunWith(SpringRunner.class)
public class PaymentConfigurationApiIntegrationTest extends ServicesTestSupport {

	private static final String OTHER_STORE = "PAYCFGB";
	private static final String PASSWORD = "Password1";
	private static final String CATALOG_USER = "pay-catalog@test.com";
	private static final String OTHER_STORE_ADMIN = "pay-admin-b@test.com";
	private static final String SECRET = "sk_test_51abcdefghijklmnop7890";
	private static final String PUBLISHABLE = "pk_test_51abcdefghijklmnop4321";
	private static final String URL = "/api/v1/private/modules/payment";

	private static boolean fixturesCreated;

	@Inject
	private PaymentService paymentService;
	@Inject
	private MerchantStoreService merchantStoreService;
	@Inject
	private MerchantConfigurationService merchantConfigurationService;
	@Inject
	private ConfigurationAuditService configurationAuditService;

	@Before
	public void fixtures() {
		if (fixturesCreated) {
			return;
		}
		PersistableAddress address = new PersistableAddress();
		address.setAddress("1 Test Street");
		address.setPostalCode("12345");
		address.setCountry("US");
		address.setCity("Miami");
		address.setStateProvince("FL");
		PersistableMerchantStore store = new PersistableMerchantStore();
		store.setCode(OTHER_STORE);
		store.setCurrency("USD");
		store.setDefaultLanguage("en");
		store.setEmail("store-b@test.com");
		store.setName(OTHER_STORE);
		store.setPhone("444-555-6666");
		store.setSupportedLanguages(Arrays.asList("en"));
		store.setAddress(address);
		assertEquals(HttpStatus.OK, testRestTemplate.exchange("/api/v1/private/store/", HttpMethod.POST,
				new HttpEntity<>(store, getHeader()), Void.class).getStatusCode());

		createUser(CATALOG_USER, "ADMIN_CATALOGUE", Constants.DEFAULT_STORE);
		createUser(OTHER_STORE_ADMIN, "ADMIN", OTHER_STORE);
		fixturesCreated = true;
	}

	private void createUser(String userName, String group, String store) {
		PersistableUser user = new PersistableUser();
		user.setDefaultLanguage("en");
		user.setEmailAddress(userName);
		user.setFirstName("Pay");
		user.setLastName("Test");
		user.setUserName(userName);
		user.setPassword(PASSWORD);
		user.setRepeatPassword(PASSWORD);
		user.setActive(true);
		user.setStore(store);
		PersistableGroup g = new PersistableGroup();
		g.setName(group);
		user.getGroups().add(g);
		ResponseEntity<String> response = testRestTemplate.exchange("/api/v1/private/user/?store=" + store,
				HttpMethod.POST, new HttpEntity<>(user, getHeader()), String.class);
		assertEquals(response.getBody(), HttpStatus.OK, response.getStatusCode());
	}

	private static IntegrationModuleConfiguration stripe(String secret, String publishable) {
		IntegrationModuleConfiguration c = new IntegrationModuleConfiguration();
		c.setCode("stripe");
		c.setActive(false);
		Map<String, String> keys = new HashMap<>();
		keys.put("secretKey", secret);
		keys.put("publishableKey", publishable);
		c.setIntegrationKeys(keys);
		return c;
	}

	private ResponseEntity<String> post(IntegrationModuleConfiguration c, HttpHeaders headers, String store) {
		return testRestTemplate.exchange(URL + "?store=" + store, HttpMethod.POST, new HttpEntity<>(c, headers),
				String.class);
	}

	private ResponseEntity<IntegrationModuleConfiguration> read(HttpHeaders headers) {
		return testRestTemplate.exchange(URL + "/stripe?store=" + Constants.DEFAULT_STORE, HttpMethod.GET,
				new HttpEntity<>(headers), IntegrationModuleConfiguration.class);
	}

	private IntegrationConfiguration stored() throws Exception {
		MerchantStore store = merchantStoreService.getByCode(Constants.DEFAULT_STORE);
		return paymentService.getPaymentModulesConfigured(store).get("stripe");
	}

	@Test
	public void secretsAreMaskedOnReadKeptWhenMaskedAndChangesAreAudited() throws Exception {
		HttpHeaders admin = getHeader();
		assertEquals(HttpStatus.OK, post(stripe(SECRET, PUBLISHABLE), admin, Constants.DEFAULT_STORE).getStatusCode());

		ResponseEntity<IntegrationModuleConfiguration> read = read(admin);
		assertEquals(HttpStatus.OK, read.getStatusCode());
		assertEquals("****7890", read.getBody().getIntegrationKeys().get("secretKey"));
		assertEquals("publishable keys are public by design", PUBLISHABLE, read.getBody().getIntegrationKeys().get("publishableKey"));

		// send the masked values back with a new publishable key: the secret stays as stored
		IntegrationModuleConfiguration update = stripe("****7890", "pk_test_rotated_00005555");
		assertEquals(HttpStatus.OK, post(update, admin, Constants.DEFAULT_STORE).getStatusCode());

		IntegrationConfiguration stored = stored();
		assertEquals(SECRET, stored.getIntegrationKeys().get("secretKey"));
		assertEquals("pk_test_rotated_00005555", stored.getIntegrationKeys().get("publishableKey"));

		MerchantStore store = merchantStoreService.getByCode(Constants.DEFAULT_STORE);
		MerchantConfiguration raw = merchantConfigurationService.getMerchantConfiguration(Constants.PAYMENT_MODULES,
				store);
		assertTrue("stored with the v2 scheme", raw.getValue().startsWith("v2:"));
		assertFalse(raw.getValue().contains(SECRET));

		List<ConfigurationAuditEntry> audit = configurationAuditService.listByStore(store, 10);
		ConfigurationAuditEntry last = audit.get(0);
		assertEquals("stripe", last.getModuleCode());
		assertEquals("admin@shopizer.com", last.getActor());
		assertEquals(ConfigurationAuditEntry.TYPE_PAYMENT, last.getConfigType());
		assertEquals("publishableKey", last.getChangedKeys());
		assertTrue(audit.stream().noneMatch(a -> a.getChangedKeys() != null
				&& (a.getChangedKeys().contains(SECRET) || a.getChangedKeys().contains("pk_test"))));

		// the same values again change nothing and add no audit row
		int rows = configurationAuditService.listByStore(store, 100).size();
		assertEquals(HttpStatus.OK, post(update, admin, Constants.DEFAULT_STORE).getStatusCode());
		assertEquals(rows, configurationAuditService.listByStore(store, 100).size());
	}

	@Test
	public void nonAdminGetsForbidden() {
		HttpHeaders catalog = getHeader(CATALOG_USER, PASSWORD);
		assertEquals(HttpStatus.FORBIDDEN, read(catalog).getStatusCode());
		assertEquals(HttpStatus.FORBIDDEN, testRestTemplate.exchange(URL, HttpMethod.GET, new HttpEntity<>(catalog),
				String.class).getStatusCode());
		assertEquals(HttpStatus.FORBIDDEN,
				post(stripe("sk_test_catalog_user_9999", PUBLISHABLE), catalog, Constants.DEFAULT_STORE).getStatusCode());
	}

	@Test
	public void adminOfAnotherStoreGetsForbidden() throws Exception {
		// seed a known configuration so the no-write check below compares real values
		assertEquals(HttpStatus.OK,
				post(stripe(SECRET, PUBLISHABLE), getHeader(), Constants.DEFAULT_STORE).getStatusCode());
		IntegrationConfiguration before = stored();
		assertEquals(SECRET, before.getIntegrationKeys().get("secretKey"));

		HttpHeaders otherAdmin = getHeader(OTHER_STORE_ADMIN, PASSWORD);
		assertEquals(HttpStatus.FORBIDDEN, read(otherAdmin).getStatusCode());
		assertEquals(HttpStatus.FORBIDDEN,
				post(stripe("sk_test_cross_store_0000", "pk_test_cross_store_0000"), otherAdmin,
						Constants.DEFAULT_STORE).getStatusCode());
		IntegrationConfiguration after = stored();
		assertEquals(SECRET, after.getIntegrationKeys().get("secretKey"));
		assertEquals(before.getIntegrationKeys(), after.getIntegrationKeys());

		// its own store is fine
		assertEquals(HttpStatus.OK, testRestTemplate.exchange(URL + "/moneyorder?store=" + OTHER_STORE,
				HttpMethod.GET, new HttpEntity<>(otherAdmin), String.class).getStatusCode());
	}

	@Test
	public void unknownStoreIsNotFound() {
		assertEquals(HttpStatus.NOT_FOUND, testRestTemplate.exchange(URL + "/stripe?store=NOSUCHSTORE",
				HttpMethod.GET, new HttpEntity<>(getHeader()), String.class).getStatusCode());
	}
}
