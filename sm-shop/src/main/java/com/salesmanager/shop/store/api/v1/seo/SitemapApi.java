package com.salesmanager.shop.store.api.v1.seo;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.merchant.MerchantStoreService;
import com.salesmanager.core.model.merchant.MerchantStore;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

/**
 * Per-store XML sitemap (sitemaps.org protocol 0.9) listing the storefront home
 * page, visible categories and available products.
 *
 * <p>URLs are absolute and point at the storefront, not at this API, so the base
 * URL is configured with {@code shopizer.storefront.base-url}.
 */
@RestController
@RequestMapping("/api/v1")
@Api(tags = { "Sitemap" })
public class SitemapApi {

  static final MediaType XML = new MediaType("application", "xml", java.nio.charset.StandardCharsets.UTF_8);

  @Inject
  private MerchantStoreService merchantStoreService;

  @Inject
  private SitemapEntries sitemapEntries;

  @Value("${shopizer.storefront.base-url:http://localhost:3000}")
  private String storefrontBaseUrl = "http://localhost:3000";

  @GetMapping(value = "/sitemap.xml")
  @ApiOperation(httpMethod = "GET", value = "Sitemap of the store's storefront pages")
  public ResponseEntity<String> sitemap(
      @RequestParam(value = "store", defaultValue = "DEFAULT") String storeCode) throws ServiceException {

    MerchantStore store = merchantStoreService.getByCode(storeCode);
    if (store == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_PLAIN)
          .body("Store not found");
    }

    String body = render(baseUrl(), sitemapEntries.list(store));
    return ResponseEntity.ok()
        .contentType(XML)
        .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
        .body(body);
  }

  private String baseUrl() {
    String base = storefrontBaseUrl == null ? "" : storefrontBaseUrl.trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base;
  }

  static String render(String baseUrl, List<SitemapEntries.Entry> entries) {
    SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd");
    day.setTimeZone(TimeZone.getTimeZone("UTC"));

    StringBuilder xml = new StringBuilder(256 + entries.size() * 96);
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
    url(xml, baseUrl + "/", null);
    for (SitemapEntries.Entry entry : entries) {
      url(xml, baseUrl + entry.getPath(),
          entry.getLastModified() == null ? null : day.format(entry.getLastModified()));
    }
    xml.append("</urlset>\n");
    return xml.toString();
  }

  private static void url(StringBuilder xml, String loc, String lastmod) {
    xml.append("  <url><loc>").append(escape(loc)).append("</loc>");
    if (lastmod != null) {
      xml.append("<lastmod>").append(lastmod).append("</lastmod>");
    }
    xml.append("</url>\n");
  }

  static String escape(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      switch (c) {
        case '&': out.append("&amp;"); break;
        case '<': out.append("&lt;"); break;
        case '>': out.append("&gt;"); break;
        case '"': out.append("&quot;"); break;
        case '\'': out.append("&apos;"); break;
        default: out.append(c);
      }
    }
    return out.toString();
  }
}
