package com.salesmanager.shop.store.api.v1.seo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
      "select c.id, d.seUrl, l.id, c.auditSection.dateModified"
          + " from Category c join c.descriptions d join d.language l"
          + " where c.merchantStore.id = :storeId and c.visible = true"
          + " and d.seUrl is not null and d.seUrl <> ''"
          + " order by c.lineage, c.sortOrder, c.id, l.id";

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
    List<Entry> entries = new ArrayList<>();
    entries.addAll(toEntries(rows(CATEGORIES, store), "/category/", defaultLanguage));
    entries.addAll(toEntries(rows(PRODUCTS, store), "/product/", defaultLanguage));
    return entries.size() > MAX_URLS ? entries.subList(0, MAX_URLS) : entries;
  }

  private List<Object[]> rows(String jpql, MerchantStore store) {
    javax.persistence.Query query = entityManager.createQuery(jpql).setParameter("storeId", store.getId());
    if (jpql.contains(":now")) {
      query.setParameter("now", new Date());
    }
    @SuppressWarnings("unchecked")
    List<Object[]> result = query.getResultList();
    return result;
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
