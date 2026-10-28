package com.newsswing.core.news.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class NewsApiExceptionHandlerTest {
  @Test
  void missingResourceExceptionUsesSanitized404RatherThanGeneric500() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(new MissingResourceController())
            .setControllerAdvice(new NewsApiExceptionHandler())
            .build();
    var response =
        mvc.perform(get("/missing-resource"))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse();
    assertThat(response.getContentType()).isEqualTo("application/problem+json");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getContentAsString())
        .contains("\"code\":\"RESOURCE_NOT_FOUND\"", "No resource exists at the requested path.")
        .doesNotContain("INTERNAL_RESOURCE_SENTINEL", "NoResourceFoundException", "stackTrace");
  }

  @TestComponent
  @RestController
  static class MissingResourceController {
    @GetMapping("/missing-resource")
    void missing() throws NoResourceFoundException {
      throw new NoResourceFoundException(
          HttpMethod.GET, "INTERNAL_RESOURCE_SENTINEL", "internal lookup detail");
    }
  }
}
