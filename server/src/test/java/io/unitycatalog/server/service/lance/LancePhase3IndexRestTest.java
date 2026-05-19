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

  @Test
  @DisplayName("Phase 3 syncIndex API syncs index metadata from external executor")
  void syncIndexSyncsMetadataFromExternalExecutor() throws Exception {
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
  @DisplayName("Phase 3 index list API returns synced indices")
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
  @DisplayName("Phase 3 syncIndex API rejects missing index_name with INVALID_ARGUMENT")
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
  @DisplayName("Phase 3 syncIndex API rejects declared-only table with ABORTED")
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

  @Test
  @DisplayName("Phase 3 describeIndex returns 404 for non-existent index")
  void describeIndexReturnsNotFoundForMissingIndex() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/describe",
            "{\"index_name\":\"missing_idx\"}");

    assertLanceErrorShape(response, 404);
    assertThat(json(response).path("message").asText()).contains("not found");
  }
}
