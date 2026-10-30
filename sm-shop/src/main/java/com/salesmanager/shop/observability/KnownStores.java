package com.salesmanager.shop.observability;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The store codes that exist, refreshed every minute. Metric tags take their store value
 * from this set only, so a client sending ?store=anything cannot create new time series:
 * the tag stays bounded by the number of real stores (capped at {@code maxStores}).
 */
@Component
public class KnownStores {

	private static final Logger LOGGER = LoggerFactory.getLogger(KnownStores.class);

	@PersistenceContext
	private EntityManager em;

	@Value("${shopizer.metrics.max-stores:500}")
	private int maxStores = 500;

	private volatile Set<String> codes = Collections.emptySet();

	@Scheduled(initialDelay = 0, fixedDelayString = "${shopizer.metrics.refresh-ms:60000}")
	public void refresh() {
		try {
			List<String> found = em.createQuery("select s.code from MerchantStore s order by s.id", String.class)
					.setMaxResults(maxStores).getResultList();
			codes = Collections.unmodifiableSet(new LinkedHashSet<>(found));
		} catch (RuntimeException e) {
			LOGGER.warn("Could not load store codes for metric tags; keeping {} known stores", codes.size(), e);
		}
	}

	public boolean contains(String code) {
		return code != null && codes.contains(code);
	}

	public Set<String> codes() {
		return codes;
	}

	/** For tests. */
	void setCodes(Set<String> codes) {
		this.codes = Collections.unmodifiableSet(new LinkedHashSet<>(codes));
	}
}
