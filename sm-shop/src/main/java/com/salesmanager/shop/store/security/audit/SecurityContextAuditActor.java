package com.salesmanager.shop.store.security.audit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.salesmanager.core.model.common.audit.AuditActor;

/** Makes AuditListener record the authenticated user in AuditSection.modifiedBy (UPDT_ID). */
@Component
public class SecurityContextAuditActor {

	static String currentUsername() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
			return null;
		}
		return auth.getName();
	}

	@PostConstruct
	public void register() {
		AuditActor.setSupplier(SecurityContextAuditActor::currentUsername);
	}

	@PreDestroy
	public void unregister() {
		AuditActor.setSupplier(null);
	}
}
