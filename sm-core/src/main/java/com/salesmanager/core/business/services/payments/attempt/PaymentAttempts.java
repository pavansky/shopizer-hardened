package com.salesmanager.core.business.services.payments.attempt;

import java.util.Date;

import javax.persistence.EntityManager;

import org.apache.commons.lang3.StringUtils;

import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.PaymentAttemptStatus;

/**
 * Shared update of an attempt row, used inside whatever transaction the caller
 * already holds (for instance together with the order status change).
 */
public final class PaymentAttempts {

	private PaymentAttempts() {
	}

	public static boolean apply(EntityManager em, Long attemptId, PaymentAttemptStatus status, String gatewayRef,
			String reason) {
		if (attemptId == null) {
			return false;
		}
		PaymentAttempt attempt = em.find(PaymentAttempt.class, attemptId);
		if (attempt == null) {
			return false;
		}
		attempt.setStatus(status);
		if (StringUtils.isNotBlank(gatewayRef)) {
			attempt.setGatewayRef(StringUtils.abbreviate(gatewayRef, 255));
		}
		if (reason != null) {
			attempt.setFailureReason(StringUtils.abbreviate(reason, 255));
		}
		attempt.setDateUpdated(new Date());
		return true;
	}
}
