package com.salesmanager.shop.store.api.v1.payment;

import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.core.business.modules.integration.payment.impl.Stripe3Payment;
import com.salesmanager.core.business.services.merchant.MerchantStoreService;
import com.salesmanager.core.business.services.order.reconciliation.PendingOrderReconciler;
import com.salesmanager.core.business.services.payments.GatewayEventRecorder;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.business.services.payments.attempt.PaymentAttemptService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;

import io.micrometer.core.instrument.MeterRegistry;

import springfox.documentation.annotations.ApiIgnore;

/**
 * Fast path for Stripe payment outcomes. The reconciler is the safety net; this
 * endpoint only shortens the time an order waits in PAYMENT_PENDING.
 *
 * It also records what only Stripe knows about: refunds issued from the Stripe
 * Dashboard (charge.refunded, and charge.refund.updated for a refund that was
 * still pending when the charge event came) go into the ledger, and disputes
 * (charge.dispute.created, charge.dispute.closed) go into the order history.
 *
 * Configure in Stripe: endpoint https://<host>/api/v1/webhooks/stripe/<storeCode>,
 * events payment_intent.succeeded, payment_intent.payment_failed,
 * payment_intent.canceled, charge.refunded, charge.refund.updated, charge.dispute.created and
 * charge.dispute.closed, and store the signing secret as the stripe3 module key
 * "webhookSecret" for that store.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@ApiIgnore
public class StripeWebhookApi {

	private static final Logger LOGGER = LoggerFactory.getLogger(StripeWebhookApi.class);
	static final String MODULE = "stripe3";
	static final String SECRET_KEY = "webhookSecret";
	static final String CHARGE_REFUNDED = "charge.refunded";
	static final String REFUND_UPDATED = "charge.refund.updated";
	static final String DISPUTE_CREATED = "charge.dispute.created";
	static final String DISPUTE_CLOSED = "charge.dispute.closed";

	private final ObjectMapper mapper = new ObjectMapper();

	@Inject
	private MerchantStoreService merchantStoreService;

	@Inject
	private PaymentService paymentService;

	@Inject
	private PaymentAttemptService paymentAttemptService;

	@Inject
	private PendingOrderReconciler reconciler;

	@Inject
	private GatewayEventRecorder gatewayEventRecorder;

	@Inject
	private ObjectProvider<MeterRegistry> meterRegistry;

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
		JsonNode object = event.path("data").path("object");
		boolean intentEvent = type.startsWith("payment_intent.");
		boolean refundEvent = REFUND_UPDATED.equals(type);
		if (!intentEvent && !refundEvent && !CHARGE_REFUNDED.equals(type) && !DISPUTE_CREATED.equals(type)
				&& !DISPUTE_CLOSED.equals(type)) {
			return ResponseEntity.ok("ignored");
		}
		// a PaymentIntent event names the intent; charge and dispute events name the intent
		// and the charge (legacy Charges payments only have the charge); a refund event's
		// object is the refund, which names both as well
		String chargeId = text(object, type.startsWith("charge.dispute.") || refundEvent ? "charge" : "id");
		PaymentAttempt attempt = intentEvent ? attempt(text(object, "id"))
				: attempt(text(object, "payment_intent"), chargeId);
		if (attempt == null || attempt.getOrderId() == null || !store.getId().equals(attempt.getMerchantId())) {
			// not ours (or another store's): acknowledge so Stripe stops retrying
			return ResponseEntity.ok("no matching payment attempt");
		}

		if (CHARGE_REFUNDED.equals(type) || refundEvent) {
			// the same path for both: the charge's refunds are listed from Stripe and
			// recorded once each by refund id, so a late success is picked up here
			return refunded(store, attempt, chargeId);
		}
		if (!intentEvent) {
			return dispute(attempt, type, object);
		}

		// Any exception here returns 500, so Stripe retries later.
		PendingOrderReconciler.Result result = reconciler.reconcileOrder(attempt.getOrderId());
		LOGGER.info("Stripe webhook {} for order {}: finalised={} failed={} waiting={}", type,
				attempt.getOrderId(), result.finalised, result.failed, result.waiting);
		return ResponseEntity.ok("processed");
	}

	/**
	 * Refunds are asked from Stripe for the charge, so the amounts recorded in the
	 * ledger never come from the webhook body. Refunds the shop issued itself are
	 * already in the ledger under the same Stripe refund id and are skipped.
	 */
	private ResponseEntity<String> refunded(MerchantStore store, PaymentAttempt attempt, String chargeId)
			throws Exception {
		if (chargeId == null || !(paymentService.getPaymentModule(MODULE) instanceof Stripe3Payment)) {
			return ResponseEntity.ok("ignored");
		}
		Stripe3Payment stripe = (Stripe3Payment) paymentService.getPaymentModule(MODULE);
		// the attempt's own module holds the key (legacy "stripe" or "stripe3"); both use the same API
		IntegrationConfiguration config = attempt.getModuleCode() == null ? null
				: paymentService.getPaymentConfiguration(attempt.getModuleCode(), store);
		if (config == null) {
			config = paymentService.getPaymentConfiguration(MODULE, store);
		}
		List<Transaction> refunds = stripe.succeededRefunds(config, chargeId);
		GatewayEventRecorder.RefundResult result = gatewayEventRecorder.recordRefunds(attempt.getOrderId(), refunds,
				"Stripe");
		count("shop.payment.webhook.refunds", "outcome", "recorded", result.recorded);
		count("shop.payment.webhook.refunds", "outcome", "rejected", result.rejected);
		LOGGER.info("Stripe charge.refunded {} for order {}: {} recorded, {} already in the ledger, {} rejected",
				chargeId, attempt.getOrderId(), result.recorded, result.alreadyRecorded, result.rejected);
		return ResponseEntity.ok("processed");
	}

	/**
	 * A dispute is written to the order history with what the merchant needs to act
	 * on in the Stripe Dashboard (reason, amount, evidence deadline). Evidence is not
	 * collected in the shop.
	 */
	private ResponseEntity<String> dispute(PaymentAttempt attempt, String type, JsonNode dispute) throws Exception {
		String disputeId = text(dispute, "id");
		if (disputeId == null) {
			return ResponseEntity.ok("ignored");
		}
		boolean created = DISPUTE_CREATED.equals(type);
		String amount = dispute.hasNonNull("amount") && dispute.hasNonNull("currency")
				? Stripe3Payment.displayAmount(dispute.path("amount").asLong(), dispute.path("currency").asText())
				: "?";
		StringBuilder comment = new StringBuilder();
		comment.append("Stripe dispute ").append(text(dispute, "status")).append(", reason ")
				.append(text(dispute, "reason")).append(", amount ").append(amount);
		long dueBy = dispute.path("evidence_details").path("due_by").asLong(0);
		if (created && dueBy > 0) {
			comment.append(", respond in the Stripe Dashboard before ").append(java.time.Instant.ofEpochSecond(dueBy));
		}
		boolean recorded = gatewayEventRecorder.recordDispute(attempt.getOrderId(), disputeId,
				created ? "opened" : "closed", comment.toString(), created);
		if (recorded) {
			count("shop.payment.disputes", "event", created ? "created" : "closed", 1);
		}
		LOGGER.warn("Stripe {} {} for order {}: {}", type, disputeId, attempt.getOrderId(), comment);
		return ResponseEntity.ok("processed");
	}

	private PaymentAttempt attempt(String... gatewayRefs) {
		for (String ref : gatewayRefs) {
			if (ref != null) {
				PaymentAttempt attempt = paymentAttemptService.findByGatewayRef(ref);
				if (attempt != null) {
					return attempt;
				}
			}
		}
		return null;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isTextual() && !value.asText().isEmpty() ? value.asText() : null;
	}

	private void count(String name, String tag, String value, int amount) {
		if (amount <= 0) {
			return;
		}
		MeterRegistry registry = meterRegistry == null ? null : meterRegistry.getIfAvailable();
		if (registry != null) {
			registry.counter(name, tag, value).increment(amount);
		}
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
