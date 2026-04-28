package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.service.lance.backend.LanceTestEchoExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LancePhase2ConfigurableBackendRestTest extends BaseLancePhase1RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  @Test
  @DisplayName("Phase 2 data endpoints can use a configured execution backend")
  void dataEndpointsCanUseConfiguredExecutionBackend() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response = postJson("/v1/table/" + TABLE_ID + "/count_rows", "{}");

    assertSuccess(response);
    JsonNode body = json(response);
    assertThat(body.path("count").asInt()).isEqualTo(0);
    assertThat(body.path("backendType").asText()).isEqualTo("test-echo");
    assertThat(body.path("command").path("operation").asText()).isEqualTo("count_rows");
    assertThat(body.path("command").path("tableId").asText()).isEqualTo(TABLE_ID);
    assertThat(body.has("payload")).as(response.contentUtf8()).isFalse();
  }

  private void createActiveTableFixture() {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION)));
  }
}
