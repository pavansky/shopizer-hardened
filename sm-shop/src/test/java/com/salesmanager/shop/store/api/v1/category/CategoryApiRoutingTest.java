package com.salesmanager.shop.store.api.v1.category;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.salesmanager.shop.store.controller.category.facade.CategoryFacade;
import com.salesmanager.shop.store.controller.user.facade.UserFacade;

/** GET /category/{id} and /category/{friendlyUrl} must route to different handlers. */
@ExtendWith(MockitoExtension.class)
class CategoryApiRoutingTest {

  @Mock private CategoryFacade categoryFacade;
  @Mock private UserFacade userFacade;
  @InjectMocks private CategoryApi api;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(api).build();
  }

  @Test
  void numericPathUsesIdLookup() throws Exception {
    mvc.perform(get("/api/v1/category/42")).andExpect(status().isOk());
    verify(categoryFacade).getById(any(), eq(42L), any());
    verify(categoryFacade, never()).getCategoryByFriendlyUrl(any(), any(), any());
  }

  @Test
  void slugPathUsesFriendlyUrlLookup() throws Exception {
    mvc.perform(get("/api/v1/category/tables")).andExpect(status().isOk());
    verify(categoryFacade).getCategoryByFriendlyUrl(any(), eq("tables"), any());
    verify(categoryFacade, never()).getById(any(), any(Long.class), any());
  }
}
