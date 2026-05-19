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
class LancePhase3MetadataSyncRestTest extends BaseLancePhase2RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  // ========== P3-SYNC-001: syncVersion 测试 ==========

  @Test
  @DisplayName("P3-SYNC-001-01: syncVersion writes version metadata")
  void syncVersionWritesVersionMetadata() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse syncResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":2,\"operation\":\"create_index\","
                + "\"manifest_path\":\"s3://bucket/table/_manifests/2.manifest\","
                + "\"created_by\":\"external-worker\"}");

    assertSuccess(syncResponse);
    JsonNode synced = json(syncResponse);
    assertThat(synced.path("version").asLong()).isEqualTo(2L);
    assertThat(synced.path("operation").asText()).isEqualTo("create_index");
    assertThat(synced.path("manifest_path").asText())
        .isEqualTo("s3://bucket/table/_manifests/2.manifest");
    assertThat(synced.path("created_by").asText()).isEqualTo("external-worker");

    AggregatedHttpResponse describeResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/describe", "{\"version\":2}");

    assertSuccess(describeResponse);
    JsonNode version = json(describeResponse);
    assertThat(version.path("version").asLong()).isEqualTo(2L);
    assertThat(version.path("operation").asText()).isEqualTo("create_index");
  }

  @Test
  @DisplayName("P3-SYNC-001-02: syncVersion upserts existing version")
  void syncVersionUpsertsExistingVersion() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":5,\"operation\":\"create\",\"stats\":{\"num_rows\":100}}"));

    AggregatedHttpResponse updateResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":5,\"operation\":\"update\",\"stats\":{\"num_rows\":200}}");

    assertSuccess(updateResponse);
    assertThat(json(updateResponse).path("operation").asText()).isEqualTo("update");
    assertThat(json(updateResponse).path("stats").isObject()).isTrue();
    assertThat(json(updateResponse).path("stats").path("num_rows").asInt()).isEqualTo(200);

    AggregatedHttpResponse describeResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/describe", "{\"version\":5}");

    assertThat(json(describeResponse).path("operation").asText()).isEqualTo("update");
  }

  @Test
  @DisplayName("P3-SYNC-001-03: syncVersion rejects missing version")
  void syncVersionRejectsMissingVersion() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"operation\":\"update\"}");

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("message").asText()).contains("version is required");
  }

  @Test
  @DisplayName("P3-SYNC-001-04: syncVersion rejects declared-only table")
  void syncVersionRejectsDeclaredOnlyTable() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_DECLARED_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"insert\"}");

    assertLanceErrorShape(response, 409);
    assertThat(json(response).path("message").asText()).contains("must be materialized");
  }

  @Test
  @DisplayName("P3-SYNC-001-05: syncVersion with full metadata fields")
  void syncVersionWithFullMetadataFields() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse syncResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":10,\"operation\":\"merge_insert\","
                + "\"manifest_path\":\"s3://bucket/table/_manifests/10.manifest\","
                + "\"manifest_size\":4096,\"etag\":\"abc123\","
                + "\"metadata\":{\"config\":{\"key\":\"value\"}},"
                + "\"stats\":{\"num_rows\":500,\"num_bytes\":10240}}");

    assertSuccess(syncResponse);
    JsonNode synced = json(syncResponse);
    assertThat(synced.path("version").asLong()).isEqualTo(10L);
    assertThat(synced.path("operation").asText()).isEqualTo("merge_insert");
    assertThat(synced.path("manifest_path").asText())
        .isEqualTo("s3://bucket/table/_manifests/10.manifest");
    assertThat(synced.path("manifest_size").asLong()).isEqualTo(4096L);
    assertThat(synced.path("etag").asText()).isEqualTo("abc123");
    assertThat(synced.path("metadata").isObject()).isTrue();
    assertThat(synced.path("stats").path("num_rows").asInt()).isEqualTo(500);
  }

  @Test
  @DisplayName("P3-SYNC-001-07: syncVersion respects explicit createdBy")
  void syncVersionRespectsExplicitCreatedBy() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse syncResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":4,\"operation\":\"delete\",\"created_by\":\"custom-executor\"}");

    assertSuccess(syncResponse);
    assertThat(json(syncResponse).path("created_by").asText()).isEqualTo("custom-executor");
  }
}
