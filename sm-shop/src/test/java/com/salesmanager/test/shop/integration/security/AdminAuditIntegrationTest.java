package com.salesmanager.test.shop.integration.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.salesmanager.core.business.repositories.system.AdminAuditEventRepository;
import com.salesmanager.core.model.system.AdminAuditEvent;
import com.salesmanager.test.shop.common.ServicesTestSupport;

/** A mutating admin request leaves an ADMIN_AUDIT row naming the admin, store, path and status. */
class AdminAuditIntegrationTest extends ServicesTestSupport {

	@Autowired
	private AdminAuditEventRepository auditRepository;

	@Test
	void categoryCreationIsAudited() {
		ResponseEntity<String> created = testRestTemplate.exchange("/api/v1/private/category?store=DEFAULT",
				HttpMethod.POST, new HttpEntity<>(category("audited-category"), getHeader()), String.class);
		assertEquals(HttpStatus.CREATED, created.getStatusCode());

		List<AdminAuditEvent> rows = auditRepository.findTop50ByUsernameOrderByIdDesc("admin@shopizer.com");
		assertTrue(rows.stream().anyMatch(r -> "POST".equals(r.getMethod())
				&& "/api/v1/private/category".equals(r.getPath())
				&& r.getStatus() == 201
				&& "DEFAULT".equals(r.getStoreCode())), String.valueOf(rows.size()));
	}

	@Test
	void legacyServicesRequestsAreAuditedToo() {
		HttpHeaders basic = new HttpHeaders();
		basic.setBasicAuth("admin@shopizer.com", "password");
		basic.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<String> deleted = testRestTemplate.exchange("/services/private/system/optin/AUDITCHECK",
				HttpMethod.DELETE, new HttpEntity<>("AUDITCHECK", basic), String.class);
		assertNotEquals(HttpStatus.UNAUTHORIZED, deleted.getStatusCode(), deleted.toString());

		List<AdminAuditEvent> rows = auditRepository.findTop50ByUsernameOrderByIdDesc("admin@shopizer.com");
		assertTrue(rows.stream().anyMatch(r -> "DELETE".equals(r.getMethod())
				&& "/services/private/system/optin/AUDITCHECK".equals(r.getPath())
				&& r.getStatus() == deleted.getStatusCodeValue()), String.valueOf(rows.size()));
	}
}
