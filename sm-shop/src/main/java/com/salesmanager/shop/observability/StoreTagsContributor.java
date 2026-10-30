package com.salesmanager.shop.observability;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.actuate.metrics.web.servlet.WebMvcTagsContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import com.salesmanager.core.business.constants.Constants;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.shop.application.config.MerchantStoreArgumentResolver;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

/**
 * Adds store={code} to http_server_requests so one merchant's failing checkouts are
 * visible instead of disappearing in the total.
 *
 * The value follows MerchantStoreArgumentResolver: the store request parameter, or the
 * DEFAULT store when the endpoint takes a MerchantStore and no parameter was sent. It is
 * only used when the store exists (KnownStores); anything else, and endpoints that are not
 * store scoped, get "none". This keeps the label bounded by the number of real stores.
 * The latency histogram multiplies by that number, so shopizer.metrics.store-tag.enabled=false
 * turns the tag off for a deployment with many stores.
 */
@Component
@ConditionalOnProperty(name = "shopizer.metrics.store-tag.enabled", havingValue = "true", matchIfMissing = true)
public class StoreTagsContributor implements WebMvcTagsContributor {

	static final String TAG = "store";
	static final String NONE = "none";

	private final KnownStores knownStores;

	public StoreTagsContributor(KnownStores knownStores) {
		this.knownStores = knownStores;
	}

	@Override
	public Iterable<Tag> getTags(HttpServletRequest request, HttpServletResponse response, Object handler,
			Throwable exception) {
		return Tags.of(TAG, storeOf(request, handler));
	}

	@Override
	public Iterable<Tag> getLongRequestTags(HttpServletRequest request, Object handler) {
		return Tags.empty();
	}

	String storeOf(HttpServletRequest request, Object handler) {
		String requested = request.getParameter(MerchantStoreArgumentResolver.REQUEST_PARAMATER_STORE);
		String store = StringUtils.isNotBlank(requested) ? requested
				: resolvesStore(handler) ? Constants.DEFAULT_STORE : null;
		return knownStores.contains(store) ? store : NONE;
	}

	private static boolean resolvesStore(Object handler) {
		if (!(handler instanceof HandlerMethod)) {
			return false;
		}
		for (MethodParameter parameter : ((HandlerMethod) handler).getMethodParameters()) {
			if (MerchantStore.class.equals(parameter.getParameterType())) {
				return true;
			}
		}
		return false;
	}
}
