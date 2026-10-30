package com.salesmanager.test.shop.integration.customer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import com.salesmanager.core.business.services.catalog.product.ProductService;
import com.salesmanager.core.business.services.catalog.product.relationship.ProductRelationshipService;
import com.salesmanager.core.business.services.catalog.product.type.ProductTypeService;
import com.salesmanager.core.business.services.merchant.MerchantStoreService;
import com.salesmanager.core.business.services.reference.country.CountryService;
import com.salesmanager.core.business.services.reference.currency.CurrencyService;
import com.salesmanager.core.business.services.reference.language.LanguageService;
import com.salesmanager.core.business.services.reference.zone.ZoneService;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.availability.ProductAvailability;
import com.salesmanager.core.model.catalog.product.description.ProductDescription;
import com.salesmanager.core.model.catalog.product.price.ProductPrice;
import com.salesmanager.core.model.catalog.product.relationship.ProductRelationship;
import com.salesmanager.core.model.catalog.product.relationship.ProductRelationshipType;
import com.salesmanager.core.model.catalog.product.type.ProductType;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.shop.application.ShopApplication;
import com.salesmanager.shop.model.catalog.product.PersistableProductReview;
import com.salesmanager.shop.model.catalog.product.ReadableProductReview;
import com.salesmanager.shop.model.customer.PersistableCustomer;
import com.salesmanager.shop.model.customer.ReadableCustomer;
import com.salesmanager.shop.model.customer.address.Address;
import com.salesmanager.shop.populator.customer.ReadableCustomerList;
import com.salesmanager.shop.store.security.AuthenticationRequest;
import com.salesmanager.shop.store.security.AuthenticationResponse;
import com.salesmanager.test.shop.common.ServicesTestSupport;

/**
 * Two stores, each with its own customers, and store B with a product that has
 * a review and a related product. Every customer endpoint, and the review and
 * related-product reads, are called on store A with store B's ids: they must
 * answer 404 (or an empty result) and leave store B's data untouched. Customer
 * tokens are bound to the store they were issued for, and the same email can be
 * registered in both stores without breaking either login.
 */
@SpringBootTest(classes = ShopApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@RunWith(SpringRunner.class)
public class CustomerStoreIsolationIntegrationTest extends ServicesTestSupport {

	private static final String STORE_A = MerchantStore.DEFAULT_STORE;
	private static final String STORE_B = "ISOB";

	private static final String SHARED_EMAIL = "iso-shared@test.com";
	private static final String PASSWORD_A = "storeA-pass1";
	private static final String PASSWORD_B = "storeB-pass1";
	private static final String EMAIL_ONLY_B = "iso-only-b@test.com";

	private static final Date DATE = new Date(System.currentTimeMillis());

	@Inject private MerchantStoreService merchantStoreService;
	@Inject private LanguageService languageService;
	@Inject private CountryService countryService;
	@Inject private CurrencyService currencyService;
	@Inject private ZoneService zoneService;
	@Inject private ProductService productService;
	@Inject private ProductTypeService productTypeService;
	@Inject private ProductRelationshipService productRelationshipService;

	// Shared by all test methods; the Spring context (and its database) is reused.
	private static boolean seeded;
	private static Long sharedCustomerA;
	private static Long sharedCustomerB;
	private static Long onlyCustomerB;
	private static Long productB;
	private static Long relatedProductB;
	private static Long reviewB;

	@Before
	public void seed() throws Exception {
		if (seeded) {
			return;
		}
		Language en = languageService.getByCode("en");
		createStore(STORE_B, en);

		sharedCustomerA = register(SHARED_EMAIL, PASSWORD_A, STORE_A).getId();
		sharedCustomerB = register(SHARED_EMAIL, PASSWORD_B, STORE_B).getId();
		onlyCustomerB = register(EMAIL_ONLY_B, PASSWORD_B, STORE_B).getId();
		assertNotNull(sharedCustomerA);
		assertNotNull(sharedCustomerB);
		assertNotNull(onlyCustomerB);
		assertNotEquals(sharedCustomerA, sharedCustomerB);

		MerchantStore storeB = merchantStoreService.getByCode(STORE_B);
		ProductType general = productTypeService.getProductType(ProductType.GENERAL_TYPE);
		Product main = productService.saveProduct(product(storeB, general, en, "ISOB-MAIN"));
		Product related = productService.saveProduct(product(storeB, general, en, "ISOB-RELATED"));
		productB = main.getId();
		relatedProductB = related.getId();

		ProductRelationship relationship = new ProductRelationship();
		relationship.setStore(storeB);
		relationship.setProduct(main);
		relationship.setRelatedProduct(related);
		relationship.setCode(ProductRelationshipType.RELATED_ITEM.name());
		relationship.setActive(true);
		productRelationshipService.saveOrUpdate(relationship);

		PersistableProductReview review = new PersistableProductReview();
		review.setCustomerId(onlyCustomerB);
		review.setRating(4d);
		review.setDescription("store B review text");
		ResponseEntity<String> created = testRestTemplate.exchange(
				"/api/v1/private/products/" + productB + "/reviews?store=" + STORE_B, HttpMethod.POST,
				new HttpEntity<>(review, getHeader()), String.class);
		assertThat(created.getStatusCode(), is(HttpStatus.CREATED));

		ReadableProductReview[] reviews = testRestTemplate.exchange(
				"/api/v1/product/" + productB + "/reviews?store=" + STORE_B, HttpMethod.GET,
				new HttpEntity<>(jsonHeaders()), ReadableProductReview[].class).getBody();
		assertNotNull(reviews);
		assertEquals(1, reviews.length);
		reviewB = reviews[0].getId();

		seeded = true;
	}

	// ---------------------------------------------------------------- /private customer endpoints

	@Test
	public void privateCustomerEndpointsOfStoreAReturn404ForStoreBIds() {
		HttpHeaders admin = getHeader();

		// read
		assertThat(exchange("/api/v1/private/customer/" + onlyCustomerB + "?store=" + STORE_A, HttpMethod.GET, null, admin)
				.getStatusCode(), is(HttpStatus.NOT_FOUND));

		// full update
		PersistableCustomer change = customer("hijacked@test.com", "whatever1", "XX");
		ResponseEntity<String> put = exchange("/api/v1/private/customer/" + onlyCustomerB + "?store=" + STORE_A,
				HttpMethod.PUT, change, admin);
		assertThat(put.getStatusCode(), is(HttpStatus.NOT_FOUND));

		// address change
		PersistableCustomer address = new PersistableCustomer();
		address.setEmailAddress(EMAIL_ONLY_B);
		Address billing = new Address();
		billing.setAddress("1 Hijack street");
		billing.setCity("Elsewhere");
		billing.setPostalCode("H0H0H0");
		billing.setCountry("CA");
		address.setBilling(billing);
		ResponseEntity<String> patch = exchange(
				"/api/v1/private/customer/" + onlyCustomerB + "/address?store=" + STORE_A, HttpMethod.PATCH, address, admin);
		assertThat(patch.getStatusCode(), is(HttpStatus.NOT_FOUND));

		// delete
		assertThat(exchange("/api/v1/private/customer/" + onlyCustomerB + "?store=" + STORE_A, HttpMethod.DELETE, null, admin)
				.getStatusCode(), is(HttpStatus.NOT_FOUND));

		// customer reviews
		assertThat(exchange("/api/v1/customers/" + onlyCustomerB + "/reviews?store=" + STORE_A, HttpMethod.GET, null, admin)
				.getStatusCode(), is(HttpStatus.NOT_FOUND));
		assertThat(exchange("/api/v1/private/customers/" + onlyCustomerB + "/reviews/1?store=" + STORE_A,
				HttpMethod.DELETE, null, admin).getStatusCode(), is(HttpStatus.NOT_FOUND));

		// list of store A never contains store B's customers
		ReadableCustomerList listA = testRestTemplate.exchange("/api/v1/private/customers?store=" + STORE_A,
				HttpMethod.GET, new HttpEntity<>(admin), ReadableCustomerList.class).getBody();
		assertNotNull(listA);
		for (ReadableCustomer c : listA.getCustomers()) {
			assertNotEquals(onlyCustomerB, c.getId());
			assertNotEquals(sharedCustomerB, c.getId());
			assertNotEquals(EMAIL_ONLY_B, c.getEmailAddress());
		}

		// store B's customer is unchanged and still in store B
		ResponseEntity<ReadableCustomer> inB = testRestTemplate.exchange(
				"/api/v1/private/customer/" + onlyCustomerB + "?store=" + STORE_B, HttpMethod.GET,
				new HttpEntity<>(admin), ReadableCustomer.class);
		assertThat(inB.getStatusCode(), is(HttpStatus.OK));
		assertEquals(EMAIL_ONLY_B, inB.getBody().getEmailAddress());
		assertTrue(inB.getBody().getBilling() == null
				|| !"1 Hijack street".equals(inB.getBody().getBilling().getAddress()));
	}

	@Test
	public void updateInOwnStoreKeepsTheCustomerInThatStore() {
		HttpHeaders admin = getHeader();
		PersistableCustomer change = customer(EMAIL_ONLY_B, null, "Renamed");
		ResponseEntity<String> put = exchange("/api/v1/private/customer/" + onlyCustomerB + "?store=" + STORE_B,
				HttpMethod.PUT, change, admin);
		assertThat(put.getStatusCode(), is(HttpStatus.OK));

		assertThat(exchange("/api/v1/private/customer/" + onlyCustomerB + "?store=" + STORE_A, HttpMethod.GET, null, admin)
				.getStatusCode(), is(HttpStatus.NOT_FOUND));
		assertThat(exchange("/api/v1/private/customer/" + onlyCustomerB + "?store=" + STORE_B, HttpMethod.GET, null, admin)
				.getStatusCode(), is(HttpStatus.OK));
	}

	// ---------------------------------------------------------------- reviews and related products

	@Test
	public void reviewAndRelatedReadsOfStoreADoNotListStoreBData() {
		ResponseEntity<String> reviewsA = exchange("/api/v1/product/" + productB + "/reviews?store=" + STORE_A,
				HttpMethod.GET, null, jsonHeaders());
		assertThat(reviewsA.getStatusCode(), is(HttpStatus.NOT_FOUND));
		assertThat(String.valueOf(reviewsA.getBody()), not(containsString("store B review text")));

		ResponseEntity<String> relatedA = exchange("/api/v1/product/" + productB + "/related?store=" + STORE_A,
				HttpMethod.GET, null, jsonHeaders());
		assertTrue(relatedA.getStatusCode().is2xxSuccessful() || relatedA.getStatusCode() == HttpStatus.NOT_FOUND);
		assertThat(String.valueOf(relatedA.getBody()), not(containsString("ISOB-RELATED")));

		// the same reads on store B do return the data, so the result above is the scoping
		ResponseEntity<String> reviewsB = exchange("/api/v1/product/" + productB + "/reviews?store=" + STORE_B,
				HttpMethod.GET, null, jsonHeaders());
		assertThat(reviewsB.getStatusCode(), is(HttpStatus.OK));
		assertThat(reviewsB.getBody(), containsString("store B review text"));

		ResponseEntity<String> relatedB = exchange("/api/v1/product/" + productB + "/related?store=" + STORE_B,
				HttpMethod.GET, null, jsonHeaders());
		assertThat(relatedB.getStatusCode(), is(HttpStatus.OK));
		assertThat(relatedB.getBody(), containsString("ISOB-RELATED"));
		assertNotNull(relatedProductB);
	}

	@Test
	public void reviewOfStoreBCannotBeDeletedThroughStoreA() {
		ResponseEntity<String> delete = exchange("/api/v1/private/products/" + productB + "/reviews/" + reviewB + "?store=" + STORE_A,
				HttpMethod.DELETE, null, getHeader());
		assertThat(delete.getStatusCode(), is(HttpStatus.NOT_FOUND));
		ResponseEntity<String> reviewsB = exchange("/api/v1/product/" + productB + "/reviews?store=" + STORE_B,
				HttpMethod.GET, null, jsonHeaders());
		assertThat(reviewsB.getBody(), containsString("store B review text"));
	}

	// ---------------------------------------------------------------- customer identity

	@Test
	public void sameEmailInTwoStoresLogsIntoEachStoreSeparately() {
		ResponseEntity<AuthenticationResponse> loginA = login(SHARED_EMAIL, PASSWORD_A, STORE_A);
		assertThat(loginA.getStatusCode(), is(HttpStatus.OK));
		assertEquals(sharedCustomerA, loginA.getBody().getId());

		ResponseEntity<AuthenticationResponse> loginB = login(SHARED_EMAIL, PASSWORD_B, STORE_B);
		assertThat(loginB.getStatusCode(), is(HttpStatus.OK));
		assertEquals(sharedCustomerB, loginB.getBody().getId());

		// store B's password does not open the store A account
		assertThat(login(SHARED_EMAIL, PASSWORD_B, STORE_A).getStatusCode(), is(HttpStatus.UNAUTHORIZED));

		// a store A login without the store parameter is the DEFAULT store
		ResponseEntity<AuthenticationResponse> noStore = testRestTemplate.postForEntity("/api/v1/customer/login",
				new HttpEntity<>(new AuthenticationRequest(SHARED_EMAIL, PASSWORD_A)), AuthenticationResponse.class);
		assertThat(noStore.getStatusCode(), is(HttpStatus.OK));
		assertEquals(sharedCustomerA, noStore.getBody().getId());
	}

	@Test
	public void customerTokenIsOnlyValidForItsStore() {
		String tokenB = login(SHARED_EMAIL, PASSWORD_B, STORE_B).getBody().getToken();
		String tokenA = login(SHARED_EMAIL, PASSWORD_A, STORE_A).getBody().getToken();

		// own store: profile of the right account
		ResponseEntity<ReadableCustomer> profileB = testRestTemplate.exchange(
				"/api/v1/auth/customer/profile?store=" + STORE_B, HttpMethod.GET,
				new HttpEntity<>(bearer(tokenB)), ReadableCustomer.class);
		assertThat(profileB.getStatusCode(), is(HttpStatus.OK));
		assertEquals(sharedCustomerB, profileB.getBody().getId());

		ResponseEntity<ReadableCustomer> profileA = testRestTemplate.exchange("/api/v1/auth/customer/profile",
				HttpMethod.GET, new HttpEntity<>(bearer(tokenA)), ReadableCustomer.class);
		assertThat(profileA.getStatusCode(), is(HttpStatus.OK));
		assertEquals(sharedCustomerA, profileA.getBody().getId());

		// store B's token against store A (explicit and default store) is refused
		assertThat(exchange("/api/v1/auth/customer/profile?store=" + STORE_A, HttpMethod.GET, null, bearer(tokenB))
				.getStatusCode(), is(HttpStatus.UNAUTHORIZED));
		assertThat(exchange("/api/v1/auth/customer/profile", HttpMethod.GET, null, bearer(tokenB))
				.getStatusCode(), is(HttpStatus.UNAUTHORIZED));
		assertThat(exchange("/api/v1/auth/customer/profile?store=" + STORE_B, HttpMethod.GET, null, bearer(tokenA))
				.getStatusCode(), is(HttpStatus.UNAUTHORIZED));
	}

	@Test
	public void registeringAnExistingEmailDoesNotRevealItOrCreateADuplicate() {
		// existing account, wrong password: same status and shape, no token, nothing created
		ResponseEntity<AuthenticationResponse> again = registerResponse(SHARED_EMAIL, "not-the-password1", STORE_A);
		assertThat(again.getStatusCode(), is(HttpStatus.OK));
		assertNotNull(again.getBody());
		assertNull(again.getBody().getId());
		assertNull(again.getBody().getToken());

		// existing account, right password: behaves like a login
		ResponseEntity<AuthenticationResponse> owner = registerResponse(SHARED_EMAIL, PASSWORD_A, STORE_A);
		assertThat(owner.getStatusCode(), is(HttpStatus.OK));
		assertEquals(sharedCustomerA, owner.getBody().getId());
		assertNotNull(owner.getBody().getToken());

		// a new email: same status and shape, with a token
		ResponseEntity<AuthenticationResponse> fresh = registerResponse("iso-fresh@test.com", PASSWORD_A, STORE_A);
		assertThat(fresh.getStatusCode(), is(HttpStatus.OK));
		assertNotNull(fresh.getBody().getId());
		assertNotNull(fresh.getBody().getToken());

		// still exactly one account for the shared email in store A, and the original password works
		ReadableCustomerList listA = testRestTemplate.exchange("/api/v1/private/customers?store=" + STORE_A,
				HttpMethod.GET, new HttpEntity<>(getHeader()), ReadableCustomerList.class).getBody();
		long shared = listA.getCustomers().stream().filter(c -> SHARED_EMAIL.equals(c.getEmailAddress())).count();
		assertEquals(1, shared);
		assertThat(login(SHARED_EMAIL, PASSWORD_A, STORE_A).getStatusCode(), is(HttpStatus.OK));
	}

	// ---------------------------------------------------------------- helpers

	private ResponseEntity<String> exchange(String url, HttpMethod method, Object body, HttpHeaders headers) {
		return testRestTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
	}

	private HttpHeaders jsonHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	private HttpHeaders bearer(String token) {
		HttpHeaders headers = jsonHeaders();
		headers.add("Authorization", "Bearer " + token);
		return headers;
	}

	private ResponseEntity<AuthenticationResponse> login(String user, String password, String store) {
		return testRestTemplate.postForEntity("/api/v1/customer/login?store=" + store,
				new HttpEntity<>(new AuthenticationRequest(user, password)), AuthenticationResponse.class);
	}

	private AuthenticationResponse register(String email, String password, String store) {
		ResponseEntity<AuthenticationResponse> response = registerResponse(email, password, store);
		assertThat(response.getStatusCode(), is(HttpStatus.OK));
		assertNotNull(response.getBody().getToken());
		return response.getBody();
	}

	private ResponseEntity<AuthenticationResponse> registerResponse(String email, String password, String store) {
		return testRestTemplate.postForEntity("/api/v1/customer/register?store=" + store,
				new HttpEntity<>(customer(email, password, "Iso"), jsonHeaders()), AuthenticationResponse.class);
	}

	private PersistableCustomer customer(String email, String password, String firstName) {
		PersistableCustomer customer = new PersistableCustomer();
		customer.setEmailAddress(email);
		customer.setPassword(password);
		customer.setGender("M");
		customer.setLanguage("en");
		Address billing = new Address();
		billing.setFirstName(firstName);
		billing.setLastName("Tester");
		billing.setCountry("CA");
		customer.setBilling(billing);
		return customer;
	}

	private void createStore(String code, Language en) throws Exception {
		if (merchantStoreService.getByCode(code) != null) {
			return;
		}
		List<Language> languages = new ArrayList<>();
		languages.add(en);
		MerchantStore store = new MerchantStore();
		store.setCountry(countryService.getByCode("CA"));
		store.setCurrency(currencyService.getByCode("CAD"));
		store.setZone(zoneService.getByCode("QC"));
		store.setDefaultLanguage(en);
		store.setLanguages(languages);
		store.setInBusinessSince(DATE);
		store.setStorename(code);
		store.setStorephone("888-888-8888");
		store.setCode(code);
		store.setStorecity("My city");
		store.setStoreaddress("1234 Street address");
		store.setStorepostalcode("H2H-2H2");
		store.setStoreEmailAddress("contact@shopizer.com");
		store.setDomainName("localhost:8080");
		store.setStoreTemplate("december");
		store.setRetailer(true);
		merchantStoreService.create(store);
	}

	private Product product(MerchantStore store, ProductType type, Language en, String sku) {
		Product product = new Product();
		product.setSku(sku);
		product.setType(type);
		product.setAvailable(true);
		product.setMerchantStore(store);

		ProductDescription description = new ProductDescription();
		description.setName(sku);
		description.setSeUrl(sku.toLowerCase());
		description.setLanguage(en);
		description.setProduct(product);
		product.getDescriptions().add(description);

		ProductAvailability availability = new ProductAvailability();
		availability.setProductDateAvailable(DATE);
		availability.setProductQuantity(10);
		availability.setRegion("*");
		availability.setProduct(product);
		product.getAvailabilities().add(availability);

		ProductPrice price = new ProductPrice();
		price.setDefaultPrice(true);
		price.setProductPriceAmount(new BigDecimal("10.00"));
		price.setProductAvailability(availability);
		availability.getPrices().add(price);
		return product;
	}
}
