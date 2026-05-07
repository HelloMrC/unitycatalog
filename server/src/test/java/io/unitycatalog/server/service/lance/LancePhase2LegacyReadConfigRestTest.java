package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.service.lance.backend.LanceTestEchoExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LancePhase2LegacyReadConfigRestTest extends BaseLancePhase1RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
    serverProperties.setProperty(Property.LANCE_EXECUTION_LEGACY_READ_ENABLED.getKey(), "true");
  }

  @Test
  @DisplayName("Phase 2 configured legacy bridge read reaches backend")
  void configuredLegacyBridgeReadReachesBackend() throws Exception {
    createUcCatalogAndSchema();
    createLegacyLanceTable();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + LEGACY_TABLE_FULL_NAME + "/stats?delimiter=.", "{}");

    assertSuccess(response);
    JsonNode command = json(response).path("command");
    assertThat(command.path("tableId").asText()).isEqualTo(LEGACY_TABLE_FULL_NAME);
    assertThat(command.path("tableUri").asText()).isNotBlank();
    assertThat(command.path("table").path("tableUri").asText()).isNotBlank();
    assertThat(command.path("legacyBridge").asBoolean()).isTrue();
    assertThat(command.path("materializeDeclaredTable").asBoolean()).isFalse();
  }
}
