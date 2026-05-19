package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.service.lance.backend.LanceTestEchoExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase3")
class LancePhase3IndexRestTest extends BaseLancePhase2RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  // ========== P3-SYNC-002: syncIndex 测试 ==========

  @Test
  @DisplayName("P3-SYNC-002-01: syncIndex writes index metadata")
  void syncIndexWritesIndexMetadata() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse syncResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"vector_idx\",\"index_type\":\"vector\","
                + "\"target_columns\":[\"embedding\"],\"distance_type\":\"cosine\","
                + "\"build_params\":{\"nlist\":128}}");

    assertSuccess(syncResponse);
    JsonNode synced = json(syncResponse);
    assertThat(synced.path("index_name").asText()).isEqualTo("vector_idx");
    assertThat(synced.path("index_type").asText()).isEqualTo("vector");
    assertThat(synced.path("status").asText()).isEqualTo("READY");
    assertThat(synced.path("target_columns").isArray()).isTrue();
    assertThat(synced.path("distance_type").asText()).isEqualTo("cosine");

    AggregatedHttpResponse describeResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/describe",
            "{\"index_name\":\"vector_idx\"}");

    assertSuccess(describeResponse);
    JsonNode index = json(describeResponse);
    assertThat(index.path("index_name").asText()).isEqualTo("vector_idx");
    assertThat(index.path("index_type").asText()).isEqualTo("vector");
  }

  @Test
  @DisplayName("P3-SYNC-002-02: syncIndex upserts existing index")
  void syncIndexUpsertsExistingIndex() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"scalar_idx\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"id\"],\"status\":\"BUILDING\"}"));

    AggregatedHttpResponse updateResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"scalar_idx\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"id\"],\"status\":\"READY\","
                + "\"stats\":{\"num_indexed_rows\":1000}}");

    assertSuccess(updateResponse);
    assertThat(json(updateResponse).path("status").asText()).isEqualTo("READY");
    assertThat(json(updateResponse).path("stats").isObject()).isTrue();

    AggregatedHttpResponse describeResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/describe",
            "{\"index_name\":\"scalar_idx\"}");

    assertThat(json(describeResponse).path("status").asText()).isEqualTo("READY");
  }

  @Test
  @DisplayName("P3-SYNC-002-03: syncIndex rejects missing index_name")
  void syncIndexRejectsMissingIndexName() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_type\":\"vector\"}");

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("message").asText()).contains("index name is required");
  }

  @Test
  @DisplayName("P3-SYNC-002-04: syncIndex rejects declared-only table")
  void syncIndexRejectsDeclaredOnlyTable() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_DECLARED_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"test_idx\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"id\"]}");

    assertLanceErrorShape(response, 409);
    assertThat(json(response).path("message").asText()).contains("must be materialized");
  }

  // ========== P3-INDEX: Index 查询测试 ==========

  @Test
  @DisplayName("P3-INDEX-001: index list returns synced indices")
  void indexListReturnsSyncedIndices() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"scalar_idx\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"id\"]}"));

    AggregatedHttpResponse listResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/list", "{}");

    assertSuccess(listResponse);
    JsonNode list = json(listResponse);
    assertThat(list.path("indices").isArray()).isTrue();
    assertThat(list.path("indices").size()).isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName("P3-INDEX-002: index list returns empty for table without indices")
  void indexListReturnsEmptyForNoIndices() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse listResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/list", "{}");

    assertSuccess(listResponse);
    assertThat(json(listResponse).path("indices").isArray()).isTrue();
    assertThat(json(listResponse).path("indices")).isEmpty();
  }

  @Test
  @DisplayName("P3-INDEX-003: index list filters by status")
  void indexListFiltersByStatus() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"building_idx\",\"index_type\":\"vector\","
                + "\"target_columns\":[\"vec\"],\"status\":\"BUILDING\"}"));
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"ready_idx\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"id\"],\"status\":\"READY\"}"));

    AggregatedHttpResponse listResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/list", "{\"status\":\"READY\"}");

    assertSuccess(listResponse);
    JsonNode list = json(listResponse);
    assertThat(list.path("indices").isArray()).isTrue();
    assertThat(list.path("indices").size()).isEqualTo(1);
    assertThat(list.path("indices").get(0).path("index_name").asText()).isEqualTo("ready_idx");
  }

  @Test
  @DisplayName("P3-INDEX-004: describeIndex returns index details")
  void describeIndexReturnsIndexDetails() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"detail_idx\",\"index_type\":\"vector\","
                + "\"target_columns\":[\"embedding\"],\"distance_type\":\"l2\","
                + "\"build_params\":{\"nlist\":256},\"stats\":{\"num_rows\":500}}"));

    AggregatedHttpResponse describeResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/describe",
            "{\"index_name\":\"detail_idx\"}");

    assertSuccess(describeResponse);
    JsonNode index = json(describeResponse);
    assertThat(index.path("index_name").asText()).isEqualTo("detail_idx");
    assertThat(index.path("index_type").asText()).isEqualTo("vector");
    assertThat(index.path("target_columns").isArray()).isTrue();
    assertThat(index.path("distance_type").asText()).isEqualTo("l2");
    assertThat(index.path("build_params").path("nlist").asInt()).isEqualTo(256);
    assertThat(index.path("stats").path("num_rows").asInt()).isEqualTo(500);
  }

  @Test
  @DisplayName("P3-INDEX-005: describeIndex returns 404 for non-existent index")
  void describeIndexReturnsNotFoundForMissingIndex() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/describe",
            "{\"index_name\":\"missing_idx\"}");

    assertLanceErrorShape(response, 404);
    assertThat(json(response).path("message").asText()).contains("not found");
  }

  @Test
  @DisplayName("P3-INDEX-006: describeIndex accepts name alias")
  void describeIndexAcceptsNameAlias() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"alias_idx\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"id\"]}"));

    AggregatedHttpResponse describeResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/describe", "{\"name\":\"alias_idx\"}");

    assertSuccess(describeResponse);
    assertThat(json(describeResponse).path("index_name").asText()).isEqualTo("alias_idx");
  }
}
