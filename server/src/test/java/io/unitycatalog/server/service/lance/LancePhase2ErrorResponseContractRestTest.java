package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.service.lance.backend.LanceTestEchoExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2ErrorResponseContractRestTest extends BaseLancePhase2RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  @Test
  @DisplayName("P2-ERROR-015 error response carries requestId header value")
  void errorResponseCarriesRequestIdHeaderValue() throws Exception {
    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/prod$team_a$missing/count_rows",
            "{}",
            Map.of("x-request-id", "phase2-error-request"));

    assertLanceErrorShape(response, 404);
    JsonNode body = json(response);
    assertThat(body.path("requestId").asText()).isEqualTo("phase2-error-request");
    assertThat(response.headers().get("x-request-id")).isEqualTo("phase2-error-request");
  }

  @Test
  @DisplayName("P2-ERROR-015 protocol errors include generated requestId")
  void protocolErrorsIncludeGeneratedRequestId() throws Exception {
    AggregatedHttpResponse response = postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", "{}");

    assertLanceErrorShape(response, 415);
    JsonNode body = json(response);
    assertThat(body.path("requestId").asText()).isNotBlank();
    assertThat(response.headers().get("x-request-id")).isEqualTo(body.path("requestId").asText());
  }

  @Test
  @DisplayName("P2-ERROR-015 backend errors expose backend_request_id")
  void backendErrorsExposeBackendRequestId() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of(
                "x-request-id", "phase2-backend-error",
                "x-lance-fake-worker-error", "timeout"));

    assertLanceErrorShape(response, 504);
    JsonNode body = json(response);
    assertThat(body.path("requestId").asText()).isEqualTo("phase2-backend-error");
    assertThat(body.path("backend_request_id").asText()).isEqualTo("test-backend-timeout");
  }
}
