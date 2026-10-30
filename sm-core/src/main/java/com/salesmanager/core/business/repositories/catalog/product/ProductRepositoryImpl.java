package com.salesmanager.core.business.repositories.catalog.product;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.TypedQuery;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.salesmanager.core.business.constants.Constants;
import com.salesmanager.core.business.utils.RepositoryHelper;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.ProductCriteria;
import com.salesmanager.core.model.catalog.product.ProductList;
import com.salesmanager.core.model.catalog.product.attribute.AttributeCriteria;
import com.salesmanager.core.model.common.GenericEntityList;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.core.model.tax.taxclass.TaxClass;

public class ProductRepositoryImpl implements ProductRepositoryCustom {

	private static final Logger LOGGER = LoggerFactory.getLogger(ProductRepositoryImpl.class);

	@PersistenceContext
	private EntityManager em;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Override
	public Product getById(Long productId, MerchantStore store) {
		return get(productId, store);
	}

	@Override
	public Product getById(Long productId) {
		return get(productId, null);
	}


	@Override
	public Product getProductWithOnlyMerchantStoreById(Long productId) {
		final String hql = "select distinct p from Product as p " +
				"join fetch p.merchantStore merch " +
				"where p.id=:pid";

		final Query q = this.em.createQuery(hql);
		q.setParameter("pid", productId);

		try {
			return (Product)q.getSingleResult();
		} catch (NoResultException ignored) {
			return null;
		}
   }



	private Product get(Long productId, MerchantStore merchant) {

		List<Integer> ids = new ArrayList<Integer>();
		if (merchant != null) {
			ids.add(merchant.getId());
			if (merchant.getParent() != null) {
				ids.add(merchant.getParent().getId());
			}
		}
		return loadDetail(productId, ids);

	}

	/**
	 * Product page loader. Selects the product with its to-one associations, then loads
	 * each collection in its own batched select (see ProductGraph). The previous single
	 * statement join-fetched about thirty associations, and the database returned the
	 * Cartesian product of descriptions, prices, categories, images, attributes and
	 * variants for one product.
	 *
	 * @param storeIds when not empty, the product must belong to one of these stores
	 */
	private Product loadDetail(Long productId, List<Integer> storeIds) {
		return inPersistenceContext(() -> {
			StringBuilder qs = new StringBuilder();
			// descriptions come with the product row (one row per description, no pagination),
			// which saves ProductGraph a statement that would select the product again; no
			// distinct, because SQL DISTINCT would compare the description text columns
			qs.append("select p from Product as p ");
			qs.append("join fetch p.merchantStore merch ");
			qs.append("left join fetch p.descriptions pdesc ");
			qs.append("left join fetch p.type type ");
			qs.append("left join fetch p.manufacturer manuf ");
			qs.append("left join fetch p.taxClass tx ");
			qs.append("where p.id=:pid ");
			// the previous inner join on descriptions made a product without any description invisible
			qs.append("and exists (select pd.id from ProductDescription pd where pd.product=p)");
			if (!storeIds.isEmpty()) {
				qs.append(" and merch.id in (:mid)");
			}

			TypedQuery<Product> q = this.em.createQuery(qs.toString(), Product.class);
			q.setParameter("pid", productId);
			if (!storeIds.isEmpty()) {
				q.setParameter("mid", storeIds);
			}

			List<Product> found = q.getResultList();
			if (found.isEmpty()) {
				return null;
			}
			// one row per description, all the same product
			Product product = found.get(0);
			ProductGraph.initialize(this.em, Collections.singletonList(product), ProductGraph.Depth.DETAIL);
			return product;
		});
	}

	/**
	 * Loads products by id with their to-one associations and returns them in the order of
	 * the ids. No collection is join-fetched, so the caller can page the ids in SQL.
	 */
	private List<Product> loadByIds(List<Long> ids) {
		if (ids.isEmpty()) {
			return new ArrayList<Product>();
		}
		TypedQuery<Product> q = this.em.createQuery("select p from Product as p "
				+ "join fetch p.merchantStore merch "
				+ "left join fetch p.type type "
				+ "left join fetch p.manufacturer manuf "
				+ "left join fetch p.taxClass tx "
				+ "where p.id in (:ids)", Product.class);
		q.setParameter("ids", ids);
		Map<Long, Product> byId = new HashMap<Long, Product>();
		for (Product p : q.getResultList()) {
			byId.put(p.getId(), p);
		}
		List<Product> ordered = new ArrayList<Product>(ids.size());
		for (Long id : ids) {
			Product p = byId.get(id);
			if (p != null) {
				ordered.add(p);
			}
		}
		return ordered;
	}

	/**
	 * Runs a multi-query load in one persistence context. A web request already has one
	 * (open-in-view), and so does a caller inside a transaction; the work then runs in it,
	 * and the loaded entities stay managed exactly as before. Without one (batch jobs,
	 * core tests) every query would get its own short-lived EntityManager and the lazy
	 * collections could not be initialized, so the load runs in a read-only transaction
	 * of its own and returns a fully initialized, detached graph.
	 */
	private <T> T inPersistenceContext(Supplier<T> work) {
		if (TransactionSynchronizationManager.hasResource(entityManagerFactory)) {
			return work.get();
		}
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setReadOnly(true);
		return template.execute(status -> work.get());
	}

	@Override
	public Product getByCode(String productCode, Language language) {

		try {

			StringBuilder qs = new StringBuilder();
			qs.append("select distinct p from Product as p ");
			qs.append("join fetch p.availabilities pa ");
			qs.append("join fetch p.descriptions pd ");
			qs.append("join fetch p.merchantStore pm ");
			qs.append("left join fetch pa.prices pap ");
			qs.append("left join fetch pap.descriptions papd ");

			qs.append("left join fetch p.categories categs ");
			qs.append("left join fetch categs.descriptions categsd ");

			// images
			qs.append("left join fetch p.images images ");
			// options
			qs.append("left join fetch p.attributes pattr ");
			qs.append("left join fetch pattr.productOption po ");
			qs.append("left join fetch po.descriptions pod ");
			qs.append("left join fetch pattr.productOptionValue pov ");
			qs.append("left join fetch pov.descriptions povd ");
			qs.append("left join fetch p.relationships pr ");
			// other lefts
			qs.append("left join fetch p.manufacturer manuf ");
			qs.append("left join fetch manuf.descriptions manufd ");
			qs.append("left join fetch p.type type ");
			qs.append("left join fetch p.taxClass tx ");

			// RENTAL
			//qs.append("left join fetch p.owner owner ");

			qs.append("where p.sku=:code ");
			qs.append("and pd.language.id=:lang and papd.language.id=:lang");
			// this cannot be done on child elements from left join
			// qs.append("and pod.languageId=:lang and povd.languageId=:lang");

			String hql = qs.toString();
			Query q = this.em.createQuery(hql);

			q.setParameter("code", productCode);
			q.setParameter("lang", language.getId());

			return (Product) q.getSingleResult();

		} catch (javax.persistence.NoResultException ers) {
			return null;
		}

	}
	
	@Override
	public Product getByCode(String productCode, MerchantStore store) {

		try {

			StringBuilder qs = new StringBuilder();
			qs.append("select distinct p from Product as p ");
			qs.append("join fetch p.descriptions pd ");
			qs.append("join fetch p.merchantStore pm ");

			qs.append("left join fetch p.categories categs ");
			qs.append("left join fetch categs.descriptions categsd ");

			// options
			qs.append("left join fetch p.attributes pattr ");
			qs.append("left join fetch pattr.productOption po ");
			qs.append("left join fetch po.descriptions pod ");
			qs.append("left join fetch pattr.productOptionValue pov ");
			qs.append("left join fetch pov.descriptions povd ");
			qs.append("left join fetch p.relationships pr ");
			// other lefts
			qs.append("left join fetch p.manufacturer manuf ");
			qs.append("left join fetch manuf.descriptions manufd ");
			qs.append("left join fetch p.type type ");


			qs.append("where p.sku=:code and pm.id=:id");

			String hql = qs.toString();
			Query q = this.em.createQuery(hql);

			q.setParameter("code", productCode);
			q.setParameter("id", store.getId());

			return (Product) q.getSingleResult();

		} catch (javax.persistence.NoResultException ers) {
			return null;
		}

	}

	/**
	 * Product by slug for the storefront. The slug is unique per store and language, so the
	 * lookup is scoped by both. It used to match the slug in every store and every language,
	 * so a shared slug could render another store's product, and an unknown slug failed
	 * with "Index 0 out of bounds" (HTTP 500) instead of returning null (HTTP 404).
	 *
	 * When the slug has no match in the request language, the store's default language is
	 * tried, so a storefront that switches language keeps resolving the product URLs it
	 * already shows (they carry the default-language slug).
	 */
	public Product getByFriendlyUrl(MerchantStore store, String seUrl, Locale locale) {

		String requested = languageCode(locale);

		List<String> regionList = new ArrayList<>();
		regionList.add("*");
		regionList.add(locale == null ? "*" : locale.getCountry());

		// one query for both languages; the store's default language comes from the database
		// because the store passed in may be detached with its language not loaded
		StringBuilder qs = new StringBuilder();
		qs.append("select p.id, pdl.code from Product as p ");
		qs.append("join p.merchantStore m ");
		qs.append("join p.descriptions pd ");
		qs.append("join pd.language pdl ");
		qs.append("where m.id=:mId ");
		qs.append("and pd.seUrl=:seUrl ");
		if (requested != null) {
			qs.append("and (pdl.code=:lang or pdl.id=m.defaultLanguage.id) ");
		}
		qs.append("and exists (select pa.id from ProductAvailability pa where pa.product=p and pa.region in (:lid)) ");
		qs.append("and p.available=true and p.dateAvailable<=:dt ");
		qs.append("order by p.id");

		Query q = this.em.createQuery(qs.toString());
		q.setParameter("mId", store.getId());
		q.setParameter("seUrl", seUrl);
		if (requested != null) {
			q.setParameter("lang", requested);
		}
		q.setParameter("lid", regionList);
		q.setParameter("dt", new Date());

		Long inRequested = null;
		Long inDefault = null;
		int matches = 0;
		for (Object row : q.getResultList()) {
			Object[] r = (Object[]) row;
			Long id = ((Number) r[0]).longValue();
			matches++;
			if (requested == null || requested.equals(r[1])) {
				if (inRequested == null) {
					inRequested = id;
				}
			} else if (inDefault == null) {
				inDefault = id;
			}
		}
		Long id = inRequested != null ? inRequested : inDefault;
		if (id == null) {
			return null;
		}
		if (matches > 1) {
			LOGGER.debug("Slug [" + seUrl + "] has " + matches + " matches in store [" + store.getCode()
					+ "], using product " + id);
		}
		return loadDetail(id, Collections.singletonList(store.getId()));

	}

	/**
	 * Language code as stored in LANGUAGE.CODE. The locale comes from new Locale(code), and
	 * Java keeps the legacy codes for three languages (he becomes iw, id becomes in, yi
	 * becomes ji), which would never match the stored code.
	 */
	static String languageCode(Locale locale) {
		if (locale == null || StringUtils.isBlank(locale.getLanguage())) {
			return null;
		}
		switch (locale.getLanguage()) {
		case "iw":
			return "he";
		case "in":
			return "id";
		case "ji":
			return "yi";
		default:
			return locale.getLanguage();
		}
	}

	@Override
	public List<Product> getProductsForLocale(MerchantStore store, Set<Long> categoryIds, Language language,
			Locale locale) {

		ProductList products = this.getProductsListForLocale(store, categoryIds, language, locale, 0, -1);

		return products.getProducts();
	}

	@Override
	public Product getProductForLocale(long productId, Language language, Locale locale) {

		List regionList = new ArrayList();
		regionList.add("*");
		regionList.add(locale.getCountry());

		StringBuilder qs = new StringBuilder();
		qs.append("select distinct p from Product as p ");
		qs.append("join fetch p.availabilities pa ");
		qs.append("join fetch p.descriptions pd ");
		qs.append("join fetch p.merchantStore pm ");
		qs.append("left join fetch pa.prices pap ");
		qs.append("left join fetch pap.descriptions papd ");

		// images
		qs.append("left join fetch p.images images ");
		// options
		qs.append("left join fetch p.attributes pattr ");
		qs.append("left join fetch pattr.productOption po ");
		qs.append("left join fetch po.descriptions pod ");
		qs.append("left join fetch pattr.productOptionValue pov ");
		qs.append("left join fetch pov.descriptions povd ");
		qs.append("left join fetch p.relationships pr ");
		// other lefts
		qs.append("left join fetch p.manufacturer manuf ");
		qs.append("left join fetch manuf.descriptions manufd ");
		qs.append("left join fetch p.type type ");
		qs.append("left join fetch p.taxClass tx ");

		// RENTAL
		//qs.append("left join fetch p.owner owner ");

		qs.append("where p.id=:pid and pa.region in (:lid) ");
		qs.append("and pd.language.id=:lang and papd.language.id=:lang ");
		qs.append("and p.available=true and p.dateAvailable<=:dt ");
		// this cannot be done on child elements from left join
		// qs.append("and pod.languageId=:lang and povd.languageId=:lang");

		String hql = qs.toString();
		Query q = this.em.createQuery(hql);

		q.setParameter("pid", productId);
		q.setParameter("lid", regionList);
		q.setParameter("dt", new Date());
		q.setParameter("lang", language.getId());

		@SuppressWarnings("unchecked")
		List<Product> results = q.getResultList();
		if (results.isEmpty())
			return null;
		else if (results.size() == 1)
			return results.get(0);
		throw new NonUniqueResultException();

	}

	@SuppressWarnings("rawtypes")
	@Override
	public List<Product> getProductsListByCategories(Set categoryIds) {

		// List regionList = new ArrayList();
		// regionList.add("*");
		// regionList.add(locale.getCountry());

		// TODO Test performance
		/**
		 * Testing in debug mode takes a long time with this query running in
		 * normal mode is fine
		 */

		StringBuilder qs = new StringBuilder();
		qs.append("select distinct p from Product as p ");
		qs.append("join fetch p.merchantStore merch ");
		qs.append("join fetch p.availabilities pa ");
		qs.append("left join fetch pa.prices pap ");

		qs.append("join fetch p.descriptions pd ");
		qs.append("join fetch p.categories categs ");

		qs.append("left join fetch pap.descriptions papd ");

		// images
		qs.append("left join fetch p.images images ");

		// options (do not need attributes for listings)
		qs.append("left join fetch p.attributes pattr ");
		qs.append("left join fetch pattr.productOption po ");
		qs.append("left join fetch po.descriptions pod ");
		qs.append("left join fetch pattr.productOptionValue pov ");
		qs.append("left join fetch pov.descriptions povd ");

		// other lefts
		qs.append("left join fetch p.manufacturer manuf ");
		qs.append("left join fetch p.type type ");
		qs.append("left join fetch p.taxClass tx ");

		// RENTAL
		//qs.append("left join fetch p.owner owner ");

		// qs.append("where pa.region in (:lid) ");
		qs.append("where categs.id in (:cid)");

		String hql = qs.toString();
		Query q = this.em.createQuery(hql);

		q.setParameter("cid", categoryIds);

		@SuppressWarnings("unchecked")
		List<Product> products = q.getResultList();

		return products;

	}

	@Override
	public List<Product> getProductsListByCategories(Set<Long> categoryIds, Language language) {

		// List regionList = new ArrayList();
		// regionList.add("*");
		// regionList.add(locale.getCountry());

		// TODO Test performance
		/**
		 * Testing in debug mode takes a long time with this query running in
		 * normal mode is fine
		 */

		StringBuilder qs = new StringBuilder();
		qs.append("select distinct p from Product as p ");
		qs.append("join fetch p.merchantStore merch ");
		qs.append("join fetch p.availabilities pa ");
		qs.append("left join fetch pa.prices pap ");

		qs.append("join fetch p.descriptions pd ");
		qs.append("join fetch p.categories categs ");
		qs.append("left join fetch pap.descriptions papd ");

		// images
		qs.append("left join fetch p.images images ");

		// options (do not need attributes for listings)
		qs.append("left join fetch p.attributes pattr ");
		qs.append("left join fetch pattr.productOption po ");
		qs.append("left join fetch po.descriptions pod ");
		qs.append("left join fetch pattr.productOptionValue pov ");
		qs.append("left join fetch pov.descriptions povd ");

		// other lefts
		qs.append("left join fetch p.manufacturer manuf ");
		qs.append("left join fetch manuf.descriptions manufd ");
		qs.append("left join fetch p.type type ");
		qs.append("left join fetch p.taxClass tx ");

		// RENTAL
		//qs.append("left join fetch p.owner owner ");

		// qs.append("where pa.region in (:lid) ");
		qs.append("where categs.id in (:cid) ");
		// qs.append("and pd.language.id=:lang and papd.language.id=:lang and
		// manufd.language.id=:lang ");
		qs.append("and pd.language.id=:lang and papd.language.id=:lang ");
		qs.append("and p.available=true and p.dateAvailable<=:dt ");

		String hql = qs.toString();
		Query q = this.em.createQuery(hql);

		q.setParameter("cid", categoryIds);
		q.setParameter("lang", language.getId());
		q.setParameter("dt", new Date());

		@SuppressWarnings("unchecked")
		List<Product> products = q.getResultList();

		return products;

	}

	@Override
	public List<Product> getProductsListByIds(Set<Long> productds) {
		return inPersistenceContext(() -> {
			TypedQuery<Long> q = this.em.createQuery("select p.id from Product as p "
					+ "where p.id in (:pid) and p.available=true and p.dateAvailable<=:dt "
					+ "and exists (select pd.id from ProductDescription pd where pd.product=p) "
					+ "order by p.id", Long.class);
			q.setParameter("pid", productds);
			q.setParameter("dt", new Date());
			List<Product> products = loadByIds(q.getResultList());
			ProductGraph.initialize(this.em, products, ProductGraph.Depth.DETAIL);
			return products;
		});
	}

	/**
	 * This query is used for category listings. All collections are not fully
	 * loaded, only the required objects so the listing page can display
	 * everything related to all products
	 */
	@SuppressWarnings({ "rawtypes", "unchecked", "unused" })
	private ProductList getProductsListForLocale(MerchantStore store, Set categoryIds, Language language, Locale locale,
			int first, int max) {

		List regionList = new ArrayList();
		regionList.add(Constants.ALL_REGIONS);
		if (locale != null) {
			regionList.add(locale.getCountry());
		}

		ProductList productList = new ProductList();

		Query countQ = this.em.createQuery(
				"select count(p) from Product as p INNER JOIN p.availabilities pa INNER JOIN p.categories categs where p.merchantStore.id=:mId and categs.id in (:cid) and pa.region in (:lid) and p.available=1 and p.dateAvailable<=:dt");

		countQ.setParameter("cid", categoryIds);
		countQ.setParameter("lid", regionList);
		countQ.setParameter("dt", new Date());
		countQ.setParameter("mId", store.getId());

		Number count = (Number) countQ.getSingleResult();

		productList.setTotalCount(count.intValue());

		if (count.intValue() == 0)
			return productList;

		StringBuilder qs = new StringBuilder();
		qs.append("select p from Product as p ");
		qs.append("join fetch p.merchantStore merch ");
		qs.append("join fetch p.availabilities pa ");
		qs.append("left join fetch pa.prices pap ");

		qs.append("join fetch p.descriptions pd ");
		qs.append("join fetch p.categories categs ");

		// not necessary
		// qs.append("join fetch pap.descriptions papd ");

		// images
		qs.append("left join fetch p.images images ");

		// options (do not need attributes for listings)
		// qs.append("left join fetch p.attributes pattr ");
		// qs.append("left join fetch pattr.productOption po ");
		// qs.append("left join fetch po.descriptions pod ");
		// qs.append("left join fetch pattr.productOptionValue pov ");
		// qs.append("left join fetch pov.descriptions povd ");

		// other lefts
		qs.append("left join fetch p.manufacturer manuf ");
		qs.append("left join fetch manuf.descriptions manufd ");
		qs.append("left join fetch p.type type ");
		qs.append("left join fetch p.taxClass tx ");

		// RENTAL
		//qs.append("left join fetch p.owner owner ");

		// qs.append("where pa.region in (:lid) ");
		qs.append("where p.merchantStore.id=mId and categs.id in (:cid) and pa.region in (:lid) ");
		// qs.append("and p.available=true and p.dateAvailable<=:dt and
		// pd.language.id=:lang and manufd.language.id=:lang");
		qs.append("and p.available=true and p.dateAvailable<=:dt and pd.language.id=:lang");
		qs.append(" order by p.sortOrder asc");

		String hql = qs.toString();
		Query q = this.em.createQuery(hql);

		q.setParameter("cid", categoryIds);
		q.setParameter("lid", regionList);
		q.setParameter("dt", new Date());
		q.setParameter("lang", language.getId());
		q.setParameter("mId", store.getId());

		q.setFirstResult(first);
		if (max > 0) {
			int maxCount = first + max;

			q.setMaxResults(Math.min(maxCount, count.intValue()));
		}

		List<Product> products = q.getResultList();
		productList.setProducts(products);

		return productList;

	}

	/**
	 * Main product listing: storefront category pages, search filters and the admin list.
	 *
	 * Two phases. The first selects one page of product ids in SQL, with the filters, a
	 * stable order (sort order, then id) and a real LIMIT/OFFSET; the count uses the same
	 * FROM and WHERE, so the total always matches the pages. The second loads only those
	 * products and initializes the associations the list mappers read, one batched select
	 * per association (ProductGraph).
	 *
	 * The previous query join-fetched about twenty collections and then set
	 * firstResult/maxResults on it. Hibernate cannot page such a query in SQL, so it read
	 * every matching row of the store (HHH000104) and paged in memory.
	 */
	@Override
	public ProductList listByStore(MerchantStore store, Language language, ProductCriteria criteria) {

		return inPersistenceContext(() -> {

			ProductList productList = new ProductList();
			Map<String, Object> params = new HashMap<String, Object>();
			String fromWhere = listingFromWhere(store, language, criteria, params);

			Query countQ = this.em.createQuery("select count(distinct p.id) " + fromWhere);
			params.forEach(countQ::setParameter);
			Number count = (Number) countQ.getSingleResult();
			productList.setTotalCount(count.intValue());

			if (count.intValue() == 0) {
				return productList;
			}

			// sortOrder must be selected because MySQL and H2 require ORDER BY columns of a
			// DISTINCT query in the select list
			Query idQ = this.em.createQuery(
					"select distinct p.id, p.sortOrder " + fromWhere + " order by p.sortOrder asc, p.id asc");
			params.forEach(idQ::setParameter);
			RepositoryHelper.paginateQuery(idQ, count, new GenericEntityList<Product>(), criteria);

			List<Long> ids = new ArrayList<Long>();
			for (Object row : idQ.getResultList()) {
				ids.add(((Number) ((Object[]) row)[0]).longValue());
			}

			List<Product> products = loadByIds(ids);
			ProductGraph.initialize(this.em, products, ProductCriteria.ORIGIN_SHOP.equals(criteria.getOrigin())
					? ProductGraph.Depth.LISTING_WITH_OPTIONS
					: ProductGraph.Depth.LISTING);
			productList.setProducts(products);
			return productList;
		});
	}

	/**
	 * FROM and WHERE shared by the listing count and the listing page, so both always
	 * apply the same filters. Fills params with the values to bind.
	 */
	String listingFromWhere(MerchantStore store, Language language, ProductCriteria criteria,
			Map<String, Object> params) {

		boolean shop = ProductCriteria.ORIGIN_SHOP.equals(criteria.getOrigin());

		StringBuilder from = new StringBuilder("from Product as p join p.descriptions pd");
		StringBuilder where = new StringBuilder(" where p.merchantStore.id=:mId");
		params.put("mId", store.getId());

		// a product without any availability row has no price, and the list mappers fail on
		// it ("No applicable inventory"); the previous page query left it out with an inner
		// join on availabilities, so the count and the page both keep that rule
		where.append(" and exists (select pa.id from ProductAvailability pa where pa.product=p)");

		if (!CollectionUtils.isEmpty(criteria.getProductIds())) {
			where.append(" and p.id in (:pId)");
			params.put("pId", criteria.getProductIds());
		}

		if (criteria.getLanguage() != null && !criteria.getLanguage().equals("_all")) {
			where.append(" and pd.language.code=:lang");
			params.put("lang", language.getCode());
		}

		if (!StringUtils.isBlank(criteria.getProductName())) {
			where.append(" and lower(pd.name) like :nm");
			params.put("nm", "%" + criteria.getProductName().toLowerCase() + "%");
		}

		if (!CollectionUtils.isEmpty(criteria.getCategoryIds())) {
			from.append(" join p.categories categs");
			where.append(" and categs.id in (:cid)");
			params.put("cid", criteria.getCategoryIds());
		}

		if (criteria.getManufacturerId() != null) {
			from.append(" join p.manufacturer manuf");
			where.append(" and manuf.id = :manufid");
			params.put("manufid", criteria.getManufacturerId());
		}

		if (!StringUtils.isBlank(criteria.getCode())) {
			where.append(" and lower(p.sku) like :sku");
			params.put("sku", "%" + criteria.getCode().toLowerCase() + "%");
		}

		// each attribute filter gets its own join, so "color=red and size=m" means a product
		// that has both attributes; parameter names never come from the request
		if (shop && CollectionUtils.isNotEmpty(criteria.getAttributeCriteria())) {
			int i = 0;
			for (AttributeCriteria attributeCriteria : criteria.getAttributeCriteria()) {
				from.append(" join p.attributes pattr").append(i)
					.append(" join pattr").append(i).append(".productOption po").append(i)
					.append(" join pattr").append(i).append(".productOptionValue pov").append(i)
					.append(" join pov").append(i).append(".descriptions povd").append(i);
				where.append(" and po").append(i).append(".code=:attrCode").append(i)
					.append(" and povd").append(i).append(".description like :attrValue").append(i);
				params.put("attrCode" + i, attributeCriteria.getAttributeCode());
				params.put("attrValue" + i, "%" + attributeCriteria.getAttributeValue() + "%");
				if (params.containsKey("lang")) {
					where.append(" and povd").append(i).append(".language.code=:lang");
				}
				i++;
			}
		}

		if (CollectionUtils.isNotEmpty(criteria.getOptionValueIds())) {
			from.append(" join p.attributes pattrv join pattrv.productOptionValue povv");
			where.append(" and povv.id in (:povid)");
			params.put("povid", criteria.getOptionValueIds());
		}

		if (criteria.getAvailable() != null) {
			if (criteria.getAvailable()) {
				where.append(" and p.available=true and p.dateAvailable<=:dt");
			} else {
				// parenthesised: without them the OR escaped the store filter in the count
				where.append(" and (p.available=false or p.dateAvailable>:dt)");
			}
			params.put("dt", new Date());
		}

		return from.append(where).toString();
	}

	@Override
	public List<Product> listByStore(MerchantStore store) {

		/**
		 * Testing in debug mode takes a long time with this query running in
		 * normal mode is fine
		 */

		StringBuilder qs = new StringBuilder();
		qs.append("select p from Product as p ");
		qs.append("join fetch p.merchantStore merch ");
		qs.append("join fetch p.availabilities pa ");
		qs.append("left join fetch pa.prices pap ");

		qs.append("join fetch p.descriptions pd ");
		qs.append("left join fetch p.categories categs ");

		qs.append("left join fetch pap.descriptions papd ");

		// images
		qs.append("left join fetch p.images images ");

		// options (do not need attributes for listings)
		qs.append("left join fetch p.attributes pattr ");
		qs.append("left join fetch pattr.productOption po ");
		qs.append("left join fetch po.descriptions pod ");
		qs.append("left join fetch pattr.productOptionValue pov ");
		qs.append("left join fetch pov.descriptions povd ");

		// other lefts
		qs.append("left join fetch p.manufacturer manuf ");
		qs.append("left join fetch manuf.descriptions manufd ");
		qs.append("left join fetch p.type type ");
		qs.append("left join fetch p.taxClass tx ");

		// RENTAL
		//qs.append("left join fetch p.owner owner ");

		// qs.append("where pa.region in (:lid) ");
		qs.append("where merch.id=:mid");

		String hql = qs.toString();
		Query q = this.em.createQuery(hql);

		q.setParameter("mid", store.getId());

		@SuppressWarnings("unchecked")
		List<Product> products = q.getResultList();

		return products;

	}

	@Override
	public List<Product> listByTaxClass(TaxClass taxClass) {

		/**
		 * Testing in debug mode takes a long time with this query running in
		 * normal mode is fine
		 */

		StringBuilder qs = new StringBuilder();
		qs.append("select p from Product as p ");
		qs.append("join fetch p.merchantStore merch ");
		qs.append("join fetch p.availabilities pa ");
		qs.append("left join fetch pa.prices pap ");

		qs.append("join fetch p.descriptions pd ");
		qs.append("join fetch p.categories categs ");

		qs.append("left join fetch pap.descriptions papd ");

		// images
		qs.append("left join fetch p.images images ");

		// options (do not need attributes for listings)
		qs.append("left join fetch p.attributes pattr ");
		qs.append("left join fetch pattr.productOption po ");
		qs.append("left join fetch po.descriptions pod ");
		qs.append("left join fetch pattr.productOptionValue pov ");
		qs.append("left join fetch pov.descriptions povd ");

		// other lefts
		qs.append("left join fetch p.manufacturer manuf ");
		qs.append("left join fetch manuf.descriptions manufd ");
		qs.append("left join fetch p.type type ");
		qs.append("left join fetch p.taxClass tx ");

		// RENTAL
		//qs.append("left join fetch p.owner owner ");

		// qs.append("where pa.region in (:lid) ");
		qs.append("where tx.id=:tid");

		String hql = qs.toString();
		Query q = this.em.createQuery(hql);

		q.setParameter("tid", taxClass.getId());

		@SuppressWarnings("unchecked")
		List<Product> products = q.getResultList();

		return products;

	}

	@Deprecated
	private String productQueryV1() {

		StringBuilder qs = new StringBuilder();
		qs.append("select distinct p from Product as p ");
		qs.append("join fetch p.availabilities pa ");
		qs.append("join fetch p.merchantStore merch ");
		qs.append("join fetch p.descriptions pd ");

		qs.append("left join fetch p.categories categs ");
		qs.append("left join fetch categs.descriptions categsd ");

		qs.append("left join fetch pa.prices pap ");
		qs.append("left join fetch pap.descriptions papd ");

		// images
		qs.append("left join fetch p.images images ");
		// options
		qs.append("left join fetch p.attributes pattr ");
		qs.append("left join fetch pattr.productOption po ");
		qs.append("left join fetch po.descriptions pod ");
		qs.append("left join fetch pattr.productOptionValue pov ");
		qs.append("left join fetch pov.descriptions povd ");

		//relations
		qs.append("left join fetch p.relationships pr ");

		// variants
		//qs.append("left join fetch pa.variants pav ");
		//qs.append("left join fetch pav.attribute pavattr ");

		// other lefts
		qs.append("left join fetch p.manufacturer manuf ");
		qs.append("left join fetch manuf.descriptions manufd ");
		qs.append("left join fetch p.type type ");
		qs.append("left join fetch p.taxClass tx ");

		// RENTAL REMOVED
		//qs.append("left join fetch p.owner owner ");
		return qs.toString();

	}
	
	


	@Override
	public Product getById(Long id, MerchantStore store, Language language) {
		return loadDetail(id, Collections.singletonList(store.getId()));
	}

}
