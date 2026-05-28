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

@Tag("lance-phase3")
class LancePhase3BoundaryAndEdgeRestTest extends BaseLancePhase2RestTest {

  private static final String P2_LEGACY_BRIDGE_TABLE_ID =
      "uc_lance_phase1$default$legacy_embeddings";

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  protected void createLegacyBridgeTableFixture() throws Exception {
    createUcCatalogAndSchema();
    createLegacyLanceTable();
  }

  // ========== 版本号边界值测试 ==========

  @Test
  @DisplayName("P3-EDGE-VERSION-01: syncVersion accepts version=0")
  void syncVersionAcceptsVersionZero() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":0,\"operation\":\"create\"}");

    assertSuccess(response);
    assertThat(json(response).path("version").asLong()).isEqualTo(0L);
  }

  @Test
  @DisplayName("P3-EDGE-VERSION-02: syncVersion accepts version=Long.MAX_VALUE")
  void syncVersionAcceptsVersionMaxValue() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":9223372036854775807,\"operation\":\"update\"}");

    assertSuccess(response);
    assertThat(json(response).path("version").asLong()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  @DisplayName("P3-EDGE-VERSION-03: syncVersion rejects negative version")
  void syncVersionRejectsNegativeVersion() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":-1,\"operation\":\"delete\"}");

    assertLanceErrorShape(response, 400);
  }

  @Test
  @DisplayName("P3-EDGE-VERSION-04: describeVersion accepts version=0")
  void describeVersionAcceptsVersionZero() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":0,\"operation\":\"create\"}"));

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/describe", "{\"version\":0}");

    assertSuccess(response);
    assertThat(json(response).path("version").asLong()).isEqualTo(0L);
  }

  // ========== 分页边界值测试 ==========

  @Test
  @DisplayName("P3-EDGE-PAGE-01: version list accepts page_size=1")
  void versionListAcceptsPageSizeOne() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"create\"}"));
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":2,\"operation\":\"update\"}"));

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/list", "{\"page_size\":1}");

    assertSuccess(response);
    JsonNode list = json(response);
    assertThat(list.path("versions").size()).isEqualTo(1);
    assertThat(list.path("next_page_token").asText()).isNotEmpty();
  }

  @Test
  @DisplayName("P3-EDGE-PAGE-02: version list ignores page_size=0")
  void versionListIgnoresPageSizeZero() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"create\"}"));

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/list", "{\"page_size\":0}");

    assertSuccess(response);
    assertThat(json(response).path("versions").size()).isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName("P3-EDGE-PAGE-03: version list ignores negative page_size")
  void versionListIgnoresNegativePageSize() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"create\"}"));

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/list", "{\"page_size\":-10}");

    assertSuccess(response);
    assertThat(json(response).path("versions").size()).isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName("P3-EDGE-PAGE-04: tag list accepts page_size=1")
  void tagListAcceptsPageSizeOne() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/tag",
            "{\"tag_name\":\"tag-a\",\"version\":1}"));
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/tag",
            "{\"tag_name\":\"tag-b\",\"version\":1}"));

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/list", "{\"page_size\":1}");

    assertSuccess(response);
    assertThat(json(response).path("tags").size()).isEqualTo(1);
  }

  @Test
  @DisplayName("P3-EDGE-PAGE-05: index list accepts page_size=1")
  void indexListAcceptsPageSizeOne() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"idx-a\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"id\"]}"));
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"idx-b\",\"index_type\":\"vector\","
                + "\"target_columns\":[\"vec\"]}"));

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/list", "{\"page_size\":1}");

    assertSuccess(response);
    assertThat(json(response).path("indices").size()).isEqualTo(1);
  }

  @Test
  @DisplayName("P3-EDGE-PAGE-06: transaction list accepts page_size=1")
  void transactionListAcceptsPageSizeOne() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-1\",\"status\":\"SUCCEEDED\"}"));
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-2\",\"status\":\"FAILED\"}"));

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/transaction/list", "{\"page_size\":1}");

    assertSuccess(response);
    assertThat(json(response).path("transactions").size()).isEqualTo(1);
  }

  // ========== Legacy Bridge 拒绝测试 ==========

  @Test
  @DisplayName("P3-EDGE-LEGACY-01: syncVersion rejects legacy bridge table")
  void syncVersionRejectsLegacyBridgeTable() throws Exception {
    createLegacyBridgeTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_LEGACY_BRIDGE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"update\"}");

    assertLanceErrorShape(response, 501);
    assertThat(json(response).path("message").asText())
        .contains("Legacy bridge Lance tables do not support");
  }

  @Test
  @DisplayName("P3-EDGE-LEGACY-02: syncTag rejects legacy bridge table")
  void syncTagRejectsLegacyBridgeTable() throws Exception {
    createLegacyBridgeTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_LEGACY_BRIDGE_TABLE_ID + "/metadata/sync/tag",
            "{\"tag_name\":\"test\",\"version\":1}");

    assertLanceErrorShape(response, 501);
    assertThat(json(response).path("message").asText())
        .contains("Legacy bridge Lance tables do not support");
  }

  @Test
  @DisplayName("P3-EDGE-LEGACY-03: syncIndex rejects legacy bridge table")
  void syncIndexRejectsLegacyBridgeTable() throws Exception {
    createLegacyBridgeTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_LEGACY_BRIDGE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"idx\",\"index_type\":\"scalar\"," + "\"target_columns\":[\"id\"]}");

    assertLanceErrorShape(response, 501);
    assertThat(json(response).path("message").asText())
        .contains("Legacy bridge Lance tables do not support");
  }

  @Test
  @DisplayName("P3-EDGE-LEGACY-04: syncTransaction rejects legacy bridge table")
  void syncTransactionRejectsLegacyBridgeTable() throws Exception {
    createLegacyBridgeTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_LEGACY_BRIDGE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-1\",\"status\":\"RUNNING\"}");

    assertLanceErrorShape(response, 501);
    assertThat(json(response).path("message").asText())
        .contains("Legacy bridge Lance tables do not support");
  }

  @Test
  @DisplayName("P3-EDGE-LEGACY-05: syncSchema rejects legacy bridge table")
  void syncSchemaRejectsLegacyBridgeTable() throws Exception {
    createLegacyBridgeTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_LEGACY_BRIDGE_TABLE_ID + "/metadata/sync/schema",
            "{\"version\":1,\"schema\":{\"fields\":[]}}");

    assertLanceErrorShape(response, 501);
    assertThat(json(response).path("message").asText())
        .contains("Legacy bridge Lance tables do not support");
  }

  @Test
  @DisplayName("P3-EDGE-LEGACY-06: version list rejects legacy bridge table")
  void versionListRejectsLegacyBridgeTable() throws Exception {
    createLegacyBridgeTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_LEGACY_BRIDGE_TABLE_ID + "/version/list", "{}");

    assertLanceErrorShape(response, 501);
    assertThat(json(response).path("message").asText())
        .contains("Legacy bridge Lance tables do not support");
  }

  @Test
  @DisplayName("P3-EDGE-LEGACY-07: tag list rejects legacy bridge table")
  void tagListRejectsLegacyBridgeTable() throws Exception {
    createLegacyBridgeTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_LEGACY_BRIDGE_TABLE_ID + "/tags/list", "{}");

    assertLanceErrorShape(response, 501);
    assertThat(json(response).path("message").asText())
        .contains("Legacy bridge Lance tables do not support");
  }

  // ========== 幂等性 Header 测试 ==========

  @Test
  @DisplayName("P3-EDGE-IDEMP-01: syncVersion accepts idempotency-key header")
  void syncVersionAcceptsIdempotencyKeyHeader() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"create\"}",
            Map.of("idempotency-key", "unique-key-12345"));

    assertSuccess(response);
    assertThat(json(response).path("version").asLong()).isEqualTo(1L);
  }

  @Test
  @DisplayName("P3-EDGE-IDEMP-02: syncTag accepts idempotency-key header")
  void syncTagAcceptsIdempotencyKeyHeader() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/tag",
            "{\"tag_name\":\"idemp-test\",\"version\":1}",
            Map.of("idempotency-key", "tag-idemp-123"));

    assertSuccess(response);
    assertThat(json(response).path("tag_name").asText()).isEqualTo("idemp-test");
  }

  @Test
  @DisplayName("P3-EDGE-IDEMP-03: syncIndex accepts idempotency-key header")
  void syncIndexAcceptsIdempotencyKeyHeader() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"idemp-idx\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"id\"]}",
            Map.of("idempotency-key", "index-idemp-456"));

    assertSuccess(response);
    assertThat(json(response).path("index_name").asText()).isEqualTo("idemp-idx");
  }

  @Test
  @DisplayName("P3-EDGE-IDEMP-04: syncTransaction accepts idempotency-key header")
  void syncTransactionAcceptsIdempotencyKeyHeader() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"idemp-tx\",\"status\":\"QUEUED\"}",
            Map.of("idempotency-key", "tx-idemp-789"));

    assertSuccess(response);
    assertThat(json(response).path("transaction_key").asText()).isEqualTo("idemp-tx");
  }

  // ========== 空值边界测试 ==========

  @Test
  @DisplayName("P3-EDGE-EMPTY-01: syncVersion accepts null metadata")
  void syncVersionAcceptsNullMetadata() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"create\",\"metadata\":null}");

    assertSuccess(response);
    assertThat(json(response).has("metadata")).isFalse();
  }

  @Test
  @DisplayName("P3-EDGE-EMPTY-02: syncVersion accepts null stats")
  void syncVersionAcceptsNullStats() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"create\",\"stats\":null}");

    assertSuccess(response);
    assertThat(json(response).has("stats")).isFalse();
  }

  @Test
  @DisplayName("P3-EDGE-EMPTY-03: syncTag accepts null metadata")
  void syncTagAcceptsNullMetadata() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/tag",
            "{\"tag_name\":\"null-meta\",\"version\":1,\"metadata\":null}");

    assertSuccess(response);
    assertThat(json(response).has("metadata")).isFalse();
  }

  @Test
  @DisplayName("P3-EDGE-EMPTY-04: syncIndex accepts null build_params")
  void syncIndexAcceptsNullBuildParams() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"null-build\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"id\"],\"build_params\":null}");

    assertSuccess(response);
    assertThat(json(response).has("build_params")).isFalse();
  }

  @Test
  @DisplayName("P3-EDGE-EMPTY-05: syncTransaction accepts null actions")
  void syncTransactionAcceptsNullActions() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"null-actions\",\"status\":\"SUCCEEDED\",\"actions\":null}");

    assertSuccess(response);
    assertThat(json(response).has("actions")).isFalse();
  }

  @Test
  @DisplayName("P3-EDGE-EMPTY-06: syncTransaction accepts null commit_metadata")
  void syncTransactionAcceptsNullCommitMetadata() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"null-commit\",\"status\":\"FAILED\","
                + "\"commit_metadata\":null}");

    assertSuccess(response);
    assertThat(json(response).has("commit_metadata")).isFalse();
  }

  @Test
  @DisplayName("P3-EDGE-EMPTY-07: syncVersion rejects blank tag_name")
  void syncVersionRejectsBlankTagName() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/tag",
            "{\"tag_name\":\"\",\"version\":1}");

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("message").asText()).contains("tag_name is required");
  }

  @Test
  @DisplayName("P3-EDGE-EMPTY-08: syncIndex rejects blank index_name")
  void syncIndexRejectsBlankIndexName() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"\",\"index_type\":\"scalar\"," + "\"target_columns\":[\"id\"]}");

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("message").asText()).contains("index name is required");
  }

  @Test
  @DisplayName("P3-EDGE-EMPTY-09: syncTransaction rejects blank transaction_key")
  void syncTransactionRejectsBlankTransactionKey() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"\",\"status\":\"QUEUED\"}");

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("message").asText()).contains("transaction_key is required");
  }

  // ========== 时间戳边界值测试 ==========

  @Test
  @DisplayName("P3-EDGE-TIME-01: syncVersion accepts explicit timestamp")
  void syncVersionAcceptsExplicitTimestamp() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"create\"," + "\"timestamp\":\"2024-01-01T00:00:00Z\"}");

    assertSuccess(response);
    assertThat(json(response).path("timestamp").asText()).startsWith("2024-01-01");
  }

  @Test
  @DisplayName("P3-EDGE-TIME-02: syncVersion accepts null timestamp (uses current)")
  void syncVersionAcceptsNullTimestamp() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"create\",\"timestamp\":null}");

    assertSuccess(response);
    assertThat(json(response).path("timestamp").asText()).isNotEmpty();
  }

  // ========== createdBy 边界测试 ==========

  @Test
  @DisplayName("P3-EDGE-CREATED-01: syncVersion accepts null createdBy")
  void syncVersionAcceptsNullCreatedBy() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"create\",\"created_by\":null}");

    assertSuccess(response);
  }

  @Test
  @DisplayName("P3-EDGE-CREATED-02: syncVersion accepts blank createdBy")
  void syncVersionAcceptsBlankCreatedBy() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"create\",\"created_by\":\"\"}");

    assertSuccess(response);
  }

  @Test
  @DisplayName("P3-EDGE-CREATED-03: syncTag accepts null createdBy")
  void syncTagAcceptsNullCreatedBy() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/tag",
            "{\"tag_name\":\"null-created\",\"version\":1,\"created_by\":null}");

    assertSuccess(response);
  }

  @Test
  @DisplayName("P3-EDGE-CREATED-04: syncIndex accepts null createdBy")
  void syncIndexAcceptsNullCreatedBy() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"null-created\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"id\"],\"created_by\":null}");

    assertSuccess(response);
  }

  @Test
  @DisplayName("P3-EDGE-CREATED-05: syncTransaction accepts null createdBy")
  void syncTransactionAcceptsNullCreatedBy() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"null-created\",\"status\":\"QUEUED\","
                + "\"created_by\":null}");

    assertSuccess(response);
  }

  // ========== 状态机顺序测试 ==========

  @Test
  @DisplayName("P3-EDGE-SEQ-01: transaction status QUEUED->RUNNING->SUCCEEDED")
  void transactionStatusQueuedRunningSucceededSequence() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"seq-tx\",\"status\":\"QUEUED\"}"));

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"seq-tx\",\"status\":\"RUNNING\"}"));

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"seq-tx\",\"status\":\"SUCCEEDED\"}");

    assertSuccess(response);
    assertThat(json(response).path("status").asText()).isEqualTo("SUCCEEDED");
  }

  @Test
  @DisplayName("P3-EDGE-SEQ-02: transaction status QUEUED->CANCELED")
  void transactionStatusQueuedCanceledSequence() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"cancel-tx\",\"status\":\"QUEUED\"}"));

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"cancel-tx\",\"status\":\"CANCELED\"}");

    assertSuccess(response);
    assertThat(json(response).path("status").asText()).isEqualTo("CANCELED");
  }

  @Test
  @DisplayName("P3-EDGE-SEQ-03: transaction status RUNNING->FAILED")
  void transactionStatusRunningFailedSequence() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"fail-tx\",\"status\":\"RUNNING\"}"));

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"fail-tx\",\"status\":\"FAILED\"}");

    assertSuccess(response);
    assertThat(json(response).path("status").asText()).isEqualTo("FAILED");
  }

  // ========== 大型 payload 测试 ==========

  @Test
  @DisplayName("P3-EDGE-LARGE-01: syncVersion accepts large metadata")
  void syncVersionAcceptsLargeMetadata() throws Exception {
    createActiveTableFixture();

    StringBuilder largeMetadata =
        new StringBuilder("{\"version\":1,\"operation\":\"create\",\"metadata\":{");
    for (int i = 0; i < 50; i++) {
      if (i > 0) largeMetadata.append(",");
      largeMetadata.append("\"key_").append(i).append("\":\"value_").append(i).append("\"");
    }
    largeMetadata.append("}}");

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version", largeMetadata.toString());

    assertSuccess(response);
    assertThat(json(response).path("metadata").isObject()).isTrue();
  }

  @Test
  @DisplayName("P3-EDGE-LARGE-02: syncSchema accepts large schema with many fields")
  void syncSchemaAcceptsLargeSchemaWithManyFields() throws Exception {
    createActiveTableFixture();

    StringBuilder largeSchema = new StringBuilder("{\"version\":1,\"schema\":{\"fields\":[");
    for (int i = 0; i < 30; i++) {
      if (i > 0) largeSchema.append(",");
      largeSchema.append("{\"name\":\"field_").append(i).append("\",\"type\":\"int64\"}");
    }
    largeSchema.append("]}}");

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/schema", largeSchema.toString());

    assertSuccess(response);
    assertThat(json(response).path("schema").path("fields").size()).isEqualTo(30);
  }

  // ========== JSON 字段类型验证 ==========

  @Test
  @DisplayName("P3-EDGE-JSON-01: syncVersion accepts array stats")
  void syncVersionAcceptsArrayStats() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"create\",\"stats\":[1,2,3]}");

    assertSuccess(response);
    assertThat(json(response).path("stats").isArray()).isTrue();
  }

  @Test
  @DisplayName("P3-EDGE-JSON-02: syncVersion accepts string metadata")
  void syncVersionAcceptsStringMetadata() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"create\",\"metadata\":\"simple-string\"}");

    assertSuccess(response);
    assertThat(json(response).path("metadata").asText()).isEqualTo("simple-string");
  }

  @Test
  @DisplayName("P3-EDGE-JSON-03: syncIndex accepts array target_columns")
  void syncIndexAcceptsArrayTargetColumns() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"multi-col\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"col1\",\"col2\",\"col3\"]}");

    assertSuccess(response);
    assertThat(json(response).path("target_columns").isArray()).isTrue();
    assertThat(json(response).path("target_columns").size()).isEqualTo(3);
  }

  // ========== describe 请求别名测试 ==========

  @Test
  @DisplayName("P3-EDGE-ALIAS-01: describeVersion accepts both version formats")
  void describeVersionAcceptsBothVersionFormats() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":99,\"operation\":\"create\"}"));

    AggregatedHttpResponse response1 =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/describe", "{\"version\":99}");

    assertSuccess(response1);
    assertThat(json(response1).path("version").asLong()).isEqualTo(99L);
  }

  // ========== status 默认值测试 ==========

  @Test
  @DisplayName("P3-EDGE-DEFAULT-01: syncIndex uses READY as default status")
  void syncIndexUsesReadyAsDefaultStatus() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"no-status\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"id\"]}");

    assertSuccess(response);
    assertThat(json(response).path("status").asText()).isEqualTo("READY");
  }

  // ========== Upsert 并发更新测试 ==========

  @Test
  @DisplayName("P3-EDGE-UPSERT-01: syncVersion upsert preserves all fields on update")
  void syncVersionUpsertPreservesAllFieldsOnUpdate() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":100,\"operation\":\"create\","
                + "\"manifest_path\":\"s3://bucket/1.manifest\","
                + "\"manifest_size\":1024}"));

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":100,\"operation\":\"update\","
                + "\"manifest_size\":2048,"
                + "\"stats\":{\"rows\":500}}");

    assertSuccess(response);
    assertThat(json(response).path("operation").asText()).isEqualTo("update");
    assertThat(json(response).path("manifest_size").asLong()).isEqualTo(2048L);
  }

  @Test
  @DisplayName("P3-EDGE-UPSERT-02: syncIndex upsert preserves index_type on update")
  void syncIndexUpsertPreservesIndexTypeOnUpdate() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"preserve-type\",\"index_type\":\"vector\","
                + "\"target_columns\":[\"vec\"],\"distance_type\":\"l2\"}"));

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"preserve-type\",\"index_type\":\"vector\","
                + "\"target_columns\":[\"vec\"],\"distance_type\":\"l2\","
                + "\"status\":\"READY\",\"stats\":{\"indexed\":1000}}");

    assertSuccess(response);
    assertThat(json(response).path("index_type").asText()).isEqualTo("vector");
    assertThat(json(response).path("distance_type").asText()).isEqualTo("l2");
  }
}
