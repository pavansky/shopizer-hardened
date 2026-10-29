package com.salesmanager.shop.store.api.v1.payment;

import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.core.business.services.merchant.MerchantStoreService;
import com.salesmanager.core.business.services.order.reconciliation.PendingOrderReconciler;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.business.services.payments.attempt.PaymentAttemptService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;

import springfox.documentation.annotations.ApiIgnore;

/**
 * Fast path for Stripe payment outcomes. The reconciler is the safety net; this
 * endpoint only shortens the time an order waits in PAYMENT_PENDING.
 *
 * Configure in Stripe: endpoint https://<host>/api/v1/webhooks/stripe/<storeCode>,
 * events payment_intent.succeeded, payment_intent.payment_failed and
 * payment_intent.canceled, and store the signing secret as the stripe3 module key
 * "webhookSecret" for that store.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@ApiIgnore
public class StripeWebhookApi {

	private static final Logger LOGGER = LoggerFactory.getLogger(StripeWebhookApi.class);
	static final String MODULE = "stripe3";
	static final String SECRET_KEY = "webhookSecret";

	private final ObjectMapper mapper = new ObjectMapper();

	@Inject
	private MerchantStoreService merchantStoreService;

	@Inject
	private PaymentService paymentService;

	@Inject
	private PaymentAttemptService paymentAttemptService;

	@Inject
	private PendingOrderReconciler reconciler;

	@PostMapping("/stripe/{store}")
	public ResponseEntity<String> stripe(@PathVariable("store") String storeCode,
			@RequestHeader(value = "Stripe-Signature", required = false) String signature,
			@RequestBody String payload) throws Exception {

		MerchantStore store = merchantStoreService.getByCode(storeCode);
		String secret = store == null ? null : webhookSecret(store);
		if (StringUtils.isBlank(secret)) {
			// unknown store or webhooks not configured: do not reveal which
			return ResponseEntity.notFound().build();
		}
		try {
			// signature over the raw body, with Stripe's default 5 minute tolerance against replays
			Webhook.constructEvent(payload, signature, secret);
		} catch (SignatureVerificationException | RuntimeException e) {
			LOGGER.warn("Rejected Stripe webhook for store {}: {}", storeCode, e.getMessage());
			return ResponseEntity.badRequest().body("invalid signature");
		}

		JsonNode event = mapper.readTree(payload);
		String type = event.path("type").asText();
		if (!type.startsWith("payment_intent.")) {
			return ResponseEntity.ok("ignored");
		}
		String intentId = event.path("data").path("object").path("id").asText(null);
		PaymentAttempt attempt = intentId == null ? null : paymentAttemptService.findByGatewayRef(intentId);
		if (attempt == null || attempt.getOrderId() == null || !store.getId().equals(attempt.getMerchantId())) {
			// not ours (or another store's): acknowledge so Stripe stops retrying
			return ResponseEntity.ok("no matching payment attempt");
		}

		// Any exception here returns 500, so Stripe retries later.
		PendingOrderReconciler.Result result = reconciler.reconcileOrder(attempt.getOrderId());
		LOGGER.info("Stripe webhook {} for order {}: finalised={} failed={} waiting={}", type,
				attempt.getOrderId(), result.finalised, result.failed, result.waiting);
		return ResponseEntity.ok("processed");
	}

	private String webhookSecret(MerchantStore store) throws Exception {
		IntegrationConfiguration config = paymentService.getPaymentConfiguration(MODULE, store);
		if (config == null) {
			return null;
		}
		Map<String, String> keys = config.getIntegrationKeys();
		return keys == null ? null : keys.get(SECRET_KEY);
	}
}
