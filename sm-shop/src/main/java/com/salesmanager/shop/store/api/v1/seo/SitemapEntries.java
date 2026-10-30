package com.salesmanager.shop.store.api.v1.seo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

import com.salesmanager.core.model.merchant.MerchantStore;

/**
 * Reads the storefront URLs of one store: visible categories and products that
 * are available today, each with the slug (SEF_URL) the storefront routes on.
 *
 * <p>The storefront has no language prefix in its URLs, so each product or
 * category yields one URL. The slug of the store's default language is used,
 * and the first other slug otherwise.
 *
 * <p>A category is listed only when it and all of its ancestors are visible, the
 * same rule the storefront menu applies. Each query is capped so that one request
 * never reads more rows than the sitemap can hold.
 */
@Component
public class SitemapEntries {

  /** The sitemap protocol allows at most 50,000 URLs in one file. */
  static final int MAX_URLS = 50_000;

  private static final String PRODUCTS =
      "select p.id, d.seUrl, l.id, p.auditSection.dateModified"
          + " from Product p join p.descriptions d join d.language l"
          + " where p.merchantStore.id = :storeId and p.available = true"
          + " and (p.dateAvailable is null or p.dateAvailable <= :now)"
          + " and d.seUrl is not null and d.seUrl <> ''"
          + " order by p.id, l.id";

  private static final String CATEGORIES =
      "select c.id, d.seUrl, l.id, c.auditSection.dateModified, c.lineage"
          + " from Category c join c.descriptions d join d.language l"
          + " where c.merchantStore.id = :storeId and c.visible = true"
          + " and d.seUrl is not null and d.seUrl <> ''"
          + " order by c.lineage, c.sortOrder, c.id, l.id";

  private static final String HIDDEN_CATEGORIES =
      "select c.id from Category c where c.merchantStore.id = :storeId and c.visible = false";

  private static final String LANGUAGE_COUNT =
      "select count(l) from MerchantStore s join s.languages l where s.id = :storeId";

  @PersistenceContext
  private EntityManager entityManager;

  /** A storefront path with an encoded slug ("/product/oak-bench") and its last modification date, if known. */
  public static final class Entry {
    private final String path;
    private final Date lastModified;

    public Entry(String path, Date lastModified) {
      this.path = path;
      this.lastModified = lastModified;
    }

    public String getPath() {
      return path;
    }

    public Date getLastModified() {
      return lastModified;
    }
  }

  @Transactional(readOnly = true)
  public List<Entry> list(MerchantStore store) {
    Integer defaultLanguage =
        store.getDefaultLanguage() != null ? store.getDefaultLanguage().getId() : null;
    // Rows come one per item and language, so this many rows always cover MAX_URLS items.
    int rowsPerItem = (int) Math.max(1L, Math.min(10L, (Long) entityManager.createQuery(LANGUAGE_COUNT)
        .setParameter("storeId", store.getId()).getSingleResult()));

    List<Entry> entries = new ArrayList<>();
    List<Object[]> categories =
        withoutHiddenAncestors(rows(CATEGORIES, store, MAX_URLS * rowsPerItem), hiddenCategoryIds(store));
    entries.addAll(toEntries(categories, "/category/", defaultLanguage));
    int remaining = MAX_URLS - Math.min(entries.size(), MAX_URLS);
    if (remaining > 0) {
      entries.addAll(toEntries(rows(PRODUCTS, store, remaining * rowsPerItem), "/product/", defaultLanguage));
    }
    return entries.size() > MAX_URLS ? entries.subList(0, MAX_URLS) : entries;
  }

  private Set<Long> hiddenCategoryIds(MerchantStore store) {
    @SuppressWarnings("unchecked")
    List<Long> ids = entityManager.createQuery(HIDDEN_CATEGORIES)
        .setParameter("storeId", store.getId()).getResultList();
    return new HashSet<>(ids);
  }

  private List<Object[]> rows(String jpql, MerchantStore store, int maxRows) {
    javax.persistence.Query query = entityManager.createQuery(jpql)
        .setParameter("storeId", store.getId())
        .setMaxResults(maxRows);
    if (jpql.contains(":now")) {
      query.setParameter("now", new Date());
    }
    @SuppressWarnings("unchecked")
    List<Object[]> result = query.getResultList();
    return result;
  }

  /**
   * Drops category rows (id, slug, languageId, dateModified, lineage) whose lineage,
   * for example "/1/5/9/", names a hidden category.
   */
  static List<Object[]> withoutHiddenAncestors(List<Object[]> rows, Set<Long> hiddenIds) {
    if (hiddenIds.isEmpty()) {
      return rows;
    }
    List<Object[]> kept = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      if (!hasHiddenAncestor((String) row[4], hiddenIds)) {
        kept.add(row);
      }
    }
    return kept;
  }

  private static boolean hasHiddenAncestor(String lineage, Set<Long> hiddenIds) {
    if (lineage == null) {
      return false;
    }
    for (String part : lineage.split("/")) {
      if (!part.isEmpty()) {
        try {
          if (hiddenIds.contains(Long.valueOf(part.trim()))) {
            return true;
          }
        } catch (NumberFormatException ignored) {
          // Lineage is written by CategoryServiceImpl as "/id/id/"; skip anything else.
        }
      }
    }
    return false;
  }

  /**
   * Rows are (id, slug, languageId, dateModified), ordered by id. Keeps one row per
   * id, preferring the default language.
   */
  static List<Entry> toEntries(List<Object[]> rows, String prefix, Integer defaultLanguage) {
    Map<Object, Object[]> chosen = new LinkedHashMap<>();
    for (Object[] row : rows) {
      Object[] current = chosen.get(row[0]);
      if (current == null || (defaultLanguage != null && defaultLanguage.equals(row[2])
          && !defaultLanguage.equals(current[2]))) {
        chosen.put(row[0], row);
      }
    }
    List<Entry> entries = new ArrayList<>(chosen.size());
    for (Object[] row : chosen.values()) {
      String slug = UriUtils.encodePathSegment(((String) row[1]).trim(), StandardCharsets.UTF_8);
      entries.add(new Entry(prefix + slug, (Date) row[3]));
    }
    return entries;
  }
}
