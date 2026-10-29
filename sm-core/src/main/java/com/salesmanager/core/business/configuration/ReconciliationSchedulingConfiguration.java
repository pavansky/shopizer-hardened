package com.salesmanager.core.business.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables @Scheduled for the core background jobs (pending order
 * reconciliation, idempotency key cleanup) so they run regardless of which
 * application module boots sm-core. Declaring it again elsewhere is harmless.
 */
@Configuration
@EnableScheduling
public class ReconciliationSchedulingConfiguration {
}
