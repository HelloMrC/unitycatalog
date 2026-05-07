package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
@Disabled("Enable after Lance Phase 2 read endpoints and fake/worker backends are implemented.")
class LancePhase2DataReadRestTest extends BaseLancePhase2RestTest {

  @Test
  @DisplayName("P2-DATA-001 query returns consumable Arrow IPC")
  void queryReturnsConsumableArrowIpc() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postQueryExpectingArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", "{}");

    assertArrowResponse(response);
  }

  @Test
  @DisplayName("P2-DATA-002 query columns are preserved in querySpec")
  void queryColumnsArePreservedInQuerySpec() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postQueryExpectingArrow(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", "{\"columns\":[\"id\",\"text\"]}"));

    assertThat(command.path("querySpec").path("columns").toString()).contains("id", "text");
  }

  @Test
  @DisplayName("P2-DATA-003 query filter is preserved in querySpec")
  void queryFilterIsPreservedInQuerySpec() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postQueryExpectingArrow(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", "{\"filter\":\"id > 1\"}"));

    assertThat(command.path("querySpec").path("filter").asText()).isEqualTo("id > 1");
  }

  @Test
  @DisplayName("P2-DATA-004 vector query parameters are fully mapped")
  void vectorQueryParametersAreFullyMapped() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postQueryExpectingArrow(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", fullQuerySpecRequest()));

    String querySpec = command.path("querySpec").toString();
    assertThat(querySpec)
        .contains("vector", "vectorColumn", "distanceType", "k", "offset", "bounds");
  }

  @Test
  @DisplayName("P2-DATA-005 ANN parameters are not dropped")
  void annParametersAreNotDropped() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postQueryExpectingArrow(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", fullQuerySpecRequest()));

    String querySpec = command.path("querySpec").toString();
    assertThat(querySpec)
        .contains("prefilter", "ef", "nprobes", "refineFactor", "fastSearch", "bypassVectorIndex");
  }

  @Test
  @DisplayName("P2-DATA-006 full text query composes with columns and filter")
  void fullTextQueryComposesWithColumnsAndFilter() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postQueryExpectingArrow(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/query",
                "{"
                    + "\"fullTextQuery\":{\"query\":\"hello\",\"columns\":[\"text\"]},"
                    + "\"columns\":[\"id\",\"text\"],"
                    + "\"filter\":\"id > 1\""
                    + "}"));

    assertThat(command.path("querySpec").toString()).contains("fullTextQuery", "hello", "id > 1");
  }

  @Test
  @DisplayName("P2-DATA-007 version query is forwarded")
  void versionQueryIsForwarded() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postQueryExpectingArrow(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", "{\"version\":1}"));

    assertThat(command.path("querySpec").path("version").asInt()).isEqualTo(1);
  }

  @Test
  @DisplayName("P2-DATA-008 withRowId is reflected in query response metadata")
  void withRowIdIsReflectedInQueryResponseMetadata() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postQueryExpectingArrow(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", "{\"withRowId\":true}"));

    assertThat(command.path("querySpec").path("withRowId").asBoolean()).isTrue();
    assertThat(command.path("responseSchema").toString()).containsIgnoringCase("row");
  }

  @Test
  @DisplayName("P2-DATA-009 count_rows returns JSON integer")
  void countRowsReturnsJsonInteger() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows",
            "{\"predicate\":\"id > 1\",\"version\":1}");

    assertSuccess(response);
    assertThat(json(response).isIntegralNumber()).isTrue();
  }

  @Test
  @DisplayName("P2-DATA-010 stats returns expected Lance statistics shape")
  void statsReturnsExpectedLanceStatisticsShape() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response = postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}");

    assertSuccess(response);
    JsonNode body = json(response);
    assertThat(body.has("totalBytes")).isTrue();
    assertThat(body.has("numRows")).isTrue();
    assertThat(body.has("numIndices")).isTrue();
    assertThat(body.has("fragmentStats") || body.has("rawJson")).isTrue();
  }

  @Test
  @DisplayName("P2-DATA-011 explain_plan returns JSON string")
  void explainPlanReturnsJsonString() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/explain_plan", "{\"verbose\":true}");

    assertSuccess(response);
    assertThat(json(response).isTextual()).isTrue();
  }

  @Test
  @DisplayName("P2-DATA-012 analyze_plan returns JSON string")
  void analyzePlanReturnsJsonString() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/analyze_plan", "{}");

    assertSuccess(response);
    assertThat(json(response).isTextual()).isTrue();
  }

  @Test
  @DisplayName("P2-DATA-013 query count and stats agree under real worker smoke")
  void queryCountAndStatsAgreeUnderWorkerSmoke() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse query =
        postQueryExpectingArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", "{}");
    AggregatedHttpResponse count =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows", "{}");
    AggregatedHttpResponse stats = postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}");

    assertArrowResponse(query);
    assertSuccess(count);
    assertSuccess(stats);
    assertThat(json(stats).path("numRows").asLong()).isEqualTo(json(count).asLong());
  }
}
