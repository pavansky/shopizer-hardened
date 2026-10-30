package com.salesmanager.shop.store.api.v1.seo;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.salesmanager.core.business.services.merchant.MerchantStoreService;
import com.salesmanager.core.model.merchant.MerchantStore;

@ExtendWith(MockitoExtension.class)
class SitemapApiTest {

  @Mock private MerchantStoreService merchantStoreService;
  @Mock private SitemapEntries sitemapEntries;
  @InjectMocks private SitemapApi api;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(api, "storefrontBaseUrl", "https://shop.example.com/");
    mvc = MockMvcBuilders.standaloneSetup(api).build();
  }

  @Test
  void listsHomeCategoriesAndProductsAsAbsoluteStorefrontUrls() throws Exception {
    MerchantStore store = new MerchantStore();
    store.setCode("CAD1");
    when(merchantStoreService.getByCode("CAD1")).thenReturn(store);
    when(sitemapEntries.list(store)).thenReturn(Arrays.asList(
        new SitemapEntries.Entry("/category/tables", null),
        new SitemapEntries.Entry("/product/oak-bench", new Date(0L))));

    mvc.perform(get("/api/v1/sitemap.xml").param("store", "CAD1"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", containsString("application/xml")))
        .andExpect(header().string("Cache-Control", containsString("max-age=3600")))
        .andExpect(content().string(containsString(
            "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">")))
        .andExpect(content().string(containsString("<loc>https://shop.example.com/</loc>")))
        .andExpect(content().string(containsString("<loc>https://shop.example.com/category/tables</loc></url>")))
        .andExpect(content().string(containsString(
            "<loc>https://shop.example.com/product/oak-bench</loc><lastmod>1970-01-01</lastmod>")))
        .andExpect(content().string(not(containsString("//category"))));
  }

  @Test
  void defaultsToTheDefaultStore() throws Exception {
    MerchantStore store = new MerchantStore();
    when(merchantStoreService.getByCode("DEFAULT")).thenReturn(store);
    when(sitemapEntries.list(store)).thenReturn(Collections.emptyList());

    mvc.perform(get("/api/v1/sitemap.xml")).andExpect(status().isOk());
  }

  @Test
  void unknownStoreIsNotFound() throws Exception {
    when(merchantStoreService.getByCode("NOPE")).thenReturn(null);

    mvc.perform(get("/api/v1/sitemap.xml").param("store", "NOPE")).andExpect(status().isNotFound());
    verify(sitemapEntries, never()).list(any());
  }

  @Test
  void escapesXmlSpecialCharacters() {
    String xml = SitemapApi.render("https://s.example",
        Collections.singletonList(new SitemapEntries.Entry("/product/a&b'c", null)));
    assertEquals(true, xml.contains("<loc>https://s.example/product/a&amp;b&apos;c</loc>"));
  }

  @Test
  void keepsOneSlugPerItemPreferringTheDefaultLanguageAndEncodesIt() {
    List<Object[]> rows = Arrays.asList(
        new Object[] { 1L, "banc-chene", 2, null },
        new Object[] { 1L, "oak-bench", 1, null },
        new Object[] { 2L, "only french", 2, null });

    List<SitemapEntries.Entry> entries = SitemapEntries.toEntries(rows, "/product/", 1);

    assertEquals(2, entries.size());
    assertEquals("/product/oak-bench", entries.get(0).getPath());
    assertEquals("/product/only%20french", entries.get(1).getPath());
  }
}
