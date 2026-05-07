package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.service.lance.backend.LanceTestEchoExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2ScalarResponseRestTest extends BaseLancePhase2RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  @Test
  @DisplayName("P2-DATA-009 count_rows returns a JSON integer body")
  void countRowsReturnsJsonIntegerBody() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows",
            "{\"predicate\":\"id > 1\",\"version\":1}");

    assertSuccess(response);
    JsonNode body = json(response);
    assertThat(body.isIntegralNumber()).as(response.contentUtf8()).isTrue();
    assertThat(body.asInt()).isEqualTo(0);
  }

  @Test
  @DisplayName("P2-DATA-011 explain_plan returns a JSON string body")
  void explainPlanReturnsJsonStringBody() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/explain_plan", "{\"verbose\":true}");

    assertSuccess(response);
    JsonNode body = json(response);
    assertThat(body.isTextual()).as(response.contentUtf8()).isTrue();
    assertThat(body.asText()).isEqualTo("test explain plan");
  }

  @Test
  @DisplayName("P2-DATA-012 analyze_plan returns a JSON string body")
  void analyzePlanReturnsJsonStringBody() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/analyze_plan", "{}");

    assertSuccess(response);
    JsonNode body = json(response);
    assertThat(body.isTextual()).as(response.contentUtf8()).isTrue();
    assertThat(body.asText()).isEqualTo("test analyze plan");
  }
}
