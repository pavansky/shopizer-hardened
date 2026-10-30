package com.salesmanager.core.model.common.audit;

import java.util.function.Supplier;

/**
 * Supplies the name of the authenticated user for AuditSection.modifiedBy.
 * The model has no dependency on Spring Security, so the web layer registers
 * the supplier at startup; without one, modifiedBy is left as the caller set it.
 */
public final class AuditActor {

	private static volatile Supplier<String> supplier = () -> null;

	private AuditActor() {
	}

	public static void setSupplier(Supplier<String> actorSupplier) {
		supplier = actorSupplier == null ? () -> null : actorSupplier;
	}

	/** The current actor, or null when unknown. Never throws. */
	public static String current() {
		try {
			return supplier.get();
		} catch (RuntimeException e) {
			return null;
		}
	}
}
