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
  @DisplayName("P2-DATA-011 explain_plan returns 501 (LanceDB lacks native API)")
  void explainPlanReturnsNotImplemented() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/explain_plan", "{\"verbose\":true}");

    assertLanceErrorShape(response, 501);
    assertThat(json(response).path("type").asText()).containsIgnoringCase("unimplemented");
  }

  @Test
  @DisplayName("P2-DATA-012 analyze_plan returns 501 (LanceDB lacks native API)")
  void analyzePlanReturnsNotImplemented() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/analyze_plan", "{}");

    assertLanceErrorShape(response, 501);
    assertThat(json(response).path("type").asText()).containsIgnoringCase("unimplemented");
  }
}
