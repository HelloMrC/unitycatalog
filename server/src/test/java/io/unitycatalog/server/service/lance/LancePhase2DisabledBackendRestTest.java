package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LancePhase2DisabledBackendRestTest extends BaseLancePhase2RestTest {

  @Test
  @DisplayName("Phase 2 data endpoints return UNIMPLEMENTED when execution backend is disabled")
  void dataEndpointsReturnUnimplementedWhenBackendIsDisabled() throws Exception {
    createActiveTableFixture();

    for (String endpoint : phase2DataEndpoints()) {
      AggregatedHttpResponse response = requestForEndpoint(endpoint.replace("{id}", TABLE_ID));

      assertLanceErrorShape(response, 501);
      assertThat(json(response).path("type").asText()).containsIgnoringCase("unimplemented");
      assertThat(json(response).path("message").asText()).contains("not configured");
    }
  }

  private List<String> phase2DataEndpoints() {
    return List.of(
        "/v1/table/{id}/query",
        "/v1/table/{id}/count_rows",
        "/v1/table/{id}/stats",
        "/v1/table/{id}/insert",
        "/v1/table/{id}/merge_insert",
        "/v1/table/{id}/update",
        "/v1/table/{id}/delete",
        "/v1/table/{id}/explain_plan",
        "/v1/table/{id}/analyze_plan",
        "/v1/table/{id}/create");
  }

  private AggregatedHttpResponse requestForEndpoint(String endpoint) {
    if (endpoint.endsWith("/insert")
        || endpoint.endsWith("/merge_insert")
        || endpoint.endsWith("/create")) {
      return postArrow(endpoint, arrowSmallStreamFixture());
    }
    return postJson(endpoint, "{}");
  }
}
