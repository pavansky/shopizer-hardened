package com.salesmanager.core.business.repositories.catalog.product;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.persistence.EntityManager;

import org.hibernate.Hibernate;

import com.salesmanager.core.model.catalog.category.Category;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.attribute.ProductAttribute;
import com.salesmanager.core.model.catalog.product.attribute.ProductOption;
import com.salesmanager.core.model.catalog.product.attribute.ProductOptionValue;
import com.salesmanager.core.model.catalog.product.availability.ProductAvailability;
import com.salesmanager.core.model.catalog.product.price.ProductPrice;
import com.salesmanager.core.model.catalog.product.variant.ProductVariant;
import com.salesmanager.core.model.catalog.product.variant.ProductVariantGroup;
import com.salesmanager.core.model.catalog.product.variant.ProductVariantImage;
import com.salesmanager.core.model.catalog.product.variation.ProductVariation;

/**
 * Loads the associations that the product mappers read, after the products themselves
 * were loaded without collection fetch joins.
 *
 * The old loaders join-fetched every association in one statement. The database then
 * returned one row per combination of description, price, category, image, attribute and
 * variant, so a product page read many times more bytes than it returned, and a page of
 * products could not be paged in SQL.
 *
 * Here each top-level collection is fetched by its own statement for all the products at
 * once (descriptions; availabilities with prices; images; categories with descriptions;
 * attributes with option and value; variants), so a statement never multiplies two
 * collections of the product. The remaining small associations (option and value
 * descriptions, variant prices and images) are initialized afterwards and load in batches
 * through hibernate.default_batch_fetch_size (DataConfiguration).
 *
 * Must run while the products are attached to an open persistence context.
 */
final class ProductGraph {

	/** what a listing card needs */
	enum Depth { LISTING, LISTING_WITH_OPTIONS, DETAIL }

	private ProductGraph() {
	}

	static void initialize(EntityManager em, Collection<Product> products, Depth depth) {
		if (products.isEmpty()) {
			return;
		}
		List<Long> ids = new ArrayList<Long>();
		for (Product p : products) {
			ids.add(p.getId());
		}
		fetch(em, ids, "left join fetch p.descriptions");
		fetch(em, ids, "left join fetch p.availabilities pa left join fetch pa.prices pap left join fetch pap.descriptions");
		fetch(em, ids, "left join fetch p.images");
		fetch(em, ids, "left join fetch p.categories c left join fetch c.descriptions");
		if (depth != Depth.LISTING) {
			fetch(em, ids, "left join fetch p.attributes a left join fetch a.productOption left join fetch a.productOptionValue");
			fetch(em, ids, "left join fetch p.variants v left join fetch v.variation left join fetch v.variationValue "
					+ "left join fetch v.productVariantGroup");
		}
		if (depth == Depth.DETAIL) {
			fetch(em, ids, "left join fetch p.relationships");
		}

		// what the statements above did not reach is loaded in batches here; for what they
		// did reach these calls do nothing
		for (Product p : products) {
			Hibernate.initialize(p.getDescriptions());
			initializeAvailabilities(p.getAvailabilities());
			Hibernate.initialize(p.getImages());
			Hibernate.initialize(p.getCategories());
			for (Category c : p.getCategories()) {
				Hibernate.initialize(c.getDescriptions());
			}
			if (p.getManufacturer() != null) {
				Hibernate.initialize(p.getManufacturer().getDescriptions());
			}
		}
		if (depth == Depth.LISTING) {
			return;
		}
		for (Product p : products) {
			Hibernate.initialize(p.getAttributes());
			for (ProductAttribute a : p.getAttributes()) {
				initializeOption(a.getProductOption());
				initializeOptionValue(a.getProductOptionValue());
			}
			Hibernate.initialize(p.getVariants());
			for (ProductVariant v : p.getVariants()) {
				initializeVariation(v.getVariation());
				initializeVariation(v.getVariationValue());
				initializeAvailabilities(v.getAvailabilities());
				ProductVariantGroup group = v.getProductVariantGroup();
				if (group != null) {
					Hibernate.initialize(group.getImages());
					for (ProductVariantImage image : group.getImages()) {
						Hibernate.initialize(image.getDescriptions());
					}
				}
			}
		}
		if (depth == Depth.DETAIL) {
			for (Product p : products) {
				Hibernate.initialize(p.getRelationships());
			}
		}
	}

	/** one statement: the products with one collection path, all ids at once */
	private static void fetch(EntityManager em, List<Long> ids, String joins) {
		em.createQuery("select distinct p from Product p " + joins + " where p.id in (:ids)", Product.class)
				.setParameter("ids", ids).getResultList();
	}

	private static void initializeAvailabilities(Collection<ProductAvailability> availabilities) {
		Hibernate.initialize(availabilities);
		for (ProductAvailability a : availabilities) {
			Hibernate.initialize(a.getPrices());
			for (ProductPrice price : a.getPrices()) {
				Hibernate.initialize(price.getDescriptions());
			}
		}
	}

	private static void initializeVariation(ProductVariation variation) {
		if (variation == null) {
			return;
		}
		Hibernate.initialize(variation);
		initializeOption(variation.getProductOption());
		initializeOptionValue(variation.getProductOptionValue());
	}

	private static void initializeOption(ProductOption option) {
		if (option != null) {
			Hibernate.initialize(option);
			Hibernate.initialize(option.getDescriptions());
		}
	}

	private static void initializeOptionValue(ProductOptionValue value) {
		if (value != null) {
			Hibernate.initialize(value);
			Hibernate.initialize(value.getDescriptions());
		}
	}
}
