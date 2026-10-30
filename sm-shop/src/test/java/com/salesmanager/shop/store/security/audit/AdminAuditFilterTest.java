package com.salesmanager.shop.store.security.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.salesmanager.core.business.repositories.system.AdminAuditEventRepository;
import com.salesmanager.core.model.common.audit.AuditActor;
import com.salesmanager.core.model.common.audit.AuditListener;
import com.salesmanager.core.model.common.audit.AuditSection;
import com.salesmanager.core.model.system.AdminAuditEvent;
import com.salesmanager.core.model.user.User;
import com.salesmanager.shop.store.security.abuse.ClientIpResolver;

class AdminAuditFilterTest {

	private AdminAuditEventRepository repository;
	private AdminAuditFilter filter;

	@BeforeEach
	void setUp() {
		repository = mock(AdminAuditEventRepository.class);
		filter = new AdminAuditFilter(repository, new ClientIpResolver(""));
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
		AuditActor.setSupplier(null);
	}

	private static void signIn(String name) {
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, null,
				Collections.singletonList(new SimpleGrantedAuthority("ROLE_AUTH"))));
	}

	private MockHttpServletRequest request(String method, String uri) {
		MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
		req.setRemoteAddr("192.0.2.10");
		return req;
	}

	@Test
	void mutatingRequestIsRecordedWithActorStoreMethodPathAndStatus() throws Exception {
		signIn("admin@shopizer.com");
		MockHttpServletRequest req = request("POST", "/api/v1/private/orders/1/refund");
		req.setParameter("store", "DEFAULT");
		req.setContent("{\"amount\":10}".getBytes());
		MockHttpServletResponse res = new MockHttpServletResponse();

		filter.doFilter(req, res, (rq, rs) -> ((HttpServletResponse) rs).setStatus(403));

		ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
		verify(repository).save(event.capture());
		AdminAuditEvent e = event.getValue();
		assertEquals("admin@shopizer.com", e.getUsername());
		assertEquals("DEFAULT", e.getStoreCode());
		assertEquals("POST", e.getMethod());
		assertEquals("/api/v1/private/orders/1/refund", e.getPath());
		assertEquals(403, e.getStatus());
		assertEquals("192.0.2.10", e.getClientIp());
		assertNotNull(e.getOccurredAt());
	}

	@Test
	void readsAndAnonymousRequestsAreNotRecorded() throws Exception {
		signIn("admin@shopizer.com");
		filter.doFilter(request("GET", "/api/v1/private/orders"), new MockHttpServletResponse(), (rq, rs) -> { });
		SecurityContextHolder.clearContext();
		filter.doFilter(request("DELETE", "/api/v1/private/product/1"), new MockHttpServletResponse(), (rq, rs) -> { });
		verify(repository, never()).save(any());
	}

	@Test
	void anExceptionIsRecordedAs500AndAFailingRepositoryDoesNotFailTheRequest() throws Exception {
		signIn("admin@shopizer.com");
		when(repository.save(any())).thenThrow(new IllegalStateException("db down"));
		try {
			filter.doFilter(request("PUT", "/api/v1/private/store/DEFAULT"), new MockHttpServletResponse(),
					(rq, rs) -> { throw new IllegalArgumentException("boom"); });
		} catch (IllegalArgumentException expected) {
			// the original exception propagates, not the audit failure
		}
		ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
		verify(repository).save(event.capture());
		assertEquals(500, event.getValue().getStatus());

		// and a plain request still completes when the audit write fails
		MockHttpServletResponse ok = new MockHttpServletResponse();
		filter.doFilter(request("POST", "/api/v1/private/category"), ok, (rq, rs) -> ((HttpServletResponse) rs).setStatus(201));
		assertEquals(201, ok.getStatus());
	}

	@Test
	void auditListenerStampsTheAuthenticatedUserInFull() {
		new SecurityContextAuditActor().register();
		signIn("a.very.long.staff.username@merchant-example.com");
		User user = new User();
		user.setAuditSection(new AuditSection());
		new AuditListener().onSave(user);
		assertEquals("a.very.long.staff.username@merchant-example.com", user.getAuditSection().getModifiedBy());

		SecurityContextHolder.clearContext();
		user.getAuditSection().setModifiedBy("populator");
		new AuditListener().onUpdate(user);
		assertEquals("populator", user.getAuditSection().getModifiedBy(), "no principal: keep what the caller set");
	}
}
