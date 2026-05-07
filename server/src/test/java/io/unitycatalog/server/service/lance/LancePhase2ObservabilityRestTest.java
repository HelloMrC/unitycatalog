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
class LancePhase2ObservabilityRestTest extends BaseLancePhase2RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  @Test
  @DisplayName("P2-AUTH-011 successful data operations are audited")
  void successfulDataOperationsAreAudited() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertSuccess(response);
    JsonNode audit = json(response).path("audit");
    assertThat(audit.path("operation").asText()).isEqualTo("insert");
    assertThat(audit.path("backendType").asText()).isEqualTo("test-echo");
    assertThat(audit.path("status").asText()).isEqualTo("success");
    assertThat(audit.path("tableVersionAfter").asLong()).isEqualTo(1);
    assertThat(audit.path("inputBytes").asLong()).isGreaterThan(0);
    assertThat(audit.has("rowsAffected")).isTrue();
  }

  @Test
  @DisplayName("P2-AUTH-012 failed backend operations are audited")
  void failedBackendOperationsAreAudited() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-worker-error", "timeout"));

    assertThat(response.status().code()).isEqualTo(504);
    JsonNode audit = json(response).path("audit");
    assertThat(audit.path("status").asText()).isEqualTo("failure");
    assertThat(audit.path("errorCode").asText()).isEqualTo("backend_timeout");
    assertThat(audit.path("backendRequestId").asText()).isEqualTo("test-backend-timeout");
  }

  @Test
  @DisplayName("P2-AUTH-013 audit redacts runtime credentials")
  void auditRedactsRuntimeCredentials() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-runtime-credential", "secret-token"));

    assertSuccess(response);
    assertThat(json(response).path("audit").toString())
        .doesNotContain("secret-token", "session", "credential");
  }

  @Test
  @DisplayName("P2-AUTH-014 metrics expose operation status latency and backend labels")
  void metricsExposeOperationStatusLatencyAndBackendLabels() {
    createActiveTableFixture();
    assertSuccess(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}"));
    postJsonWithHeaders(
        "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
        "{}",
        Map.of("x-lance-fake-worker-error", "timeout"));

    AggregatedHttpResponse metrics = getRaw("/metrics", Map.of());

    assertThat(metrics.status().code()).isEqualTo(200);
    assertThat(metrics.contentUtf8())
        .contains("lance_data_requests_total")
        .contains("lance_data_request_latency_ms")
        .contains("lance_data_backend_errors_total")
        .contains("operation=\"stats\"")
        .contains("status=\"success\"")
        .contains("status=\"failure\"")
        .contains("backend=\"test-echo\"");
  }
}
