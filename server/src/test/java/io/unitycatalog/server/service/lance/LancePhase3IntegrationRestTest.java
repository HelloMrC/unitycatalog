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

/**
 * Phase 3 Integration Tests.
 *
 * <p>These tests verify end-to-end flows across multiple APIs:
 *
 * <ul>
 *   <li>Write → Version sync → Query flow
 *   <li>Index creation → sync → Query flow
 *   <li>Transaction execution → sync → Query flow
 *   <li>Tag creation on existing version → Query flow
 *   <li>Schema evolution → sync → Query flow
 *   <li>Concurrent operations consistency
 *   <li>Metadata consistency across UC tables
 *   <li>Worker execution → sync chain validation
 * </ul>
 *
 * <p>Reference: docs/lance/unitycatalog-lancedb-phase-3-metadata-sync-test-design.md Section 18:
 * Protocol Forward Tests (P3-PROTO) Section 12.2: Concurrent Consistency Tests
 */
@Tag("lance-phase3")
@Tag("lance-integration")
class LancePhase3IntegrationRestTest extends BaseLancePhase2RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  // ========== E2E-001: Write → Version Sync → Query Flow ==========

  @Test
  @DisplayName("P3-E2E-001-01: Insert produces version, version queryable after sync")
  void insertProducesVersionQueryableAfterSync() throws Exception {
    createActiveTableFixture();

    // Step 1: Insert data via Phase 2 data plane
    AggregatedHttpResponse insertResponse =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture());
    assertSuccess(insertResponse);

    // Step 2: Worker should have synced version (echo backend simulates this)
    // Query version list
    AggregatedHttpResponse versionListResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/list", "{}");

    assertSuccess(versionListResponse);
    JsonNode versions = json(versionListResponse).path("versions");
    assertThat(versions.isArray()).isTrue();
    assertThat(versions.size()).isGreaterThanOrEqualTo(1);

    // Step 3: Query specific version details
    Long firstVersion = versions.get(0).path("version").asLong();
    AggregatedHttpResponse versionDescribeResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/describe",
            "{\"version\":" + firstVersion + "}");

    assertSuccess(versionDescribeResponse);
    assertThat(json(versionDescribeResponse).path("version").asLong()).isEqualTo(firstVersion);
    assertThat(json(versionDescribeResponse).path("operation").asText()).isNotEmpty();
  }

  @Test
  @DisplayName("P3-E2E-001-02: Multiple sync versions, ordered DESC")
  void multipleSyncVersionsOrderedDesc() throws Exception {
    createActiveTableFixture();

    // Sync multiple versions directly
    for (int i = 1; i <= 3; i++) {
      assertSuccess(
          postJson(
              "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
              "{\"version\":" + i + ",\"operation\":\"insert\"}"));
    }

    AggregatedHttpResponse versionListResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/list", "{}");

    assertSuccess(versionListResponse);
    JsonNode versions = json(versionListResponse).path("versions");
    assertThat(versions.size()).isEqualTo(3);

    // Verify DESC ordering (latest first)
    assertThat(versions.get(0).path("version").asLong()).isEqualTo(3L);
    assertThat(versions.get(1).path("version").asLong()).isEqualTo(2L);
    assertThat(versions.get(2).path("version").asLong()).isEqualTo(1L);
  }

  // ========== E2E-002: Version → Tag Creation → Query Flow ==========

  @Test
  @DisplayName("P3-E2E-002-01: Create tag on existing version, tag queryable")
  void createTagOnExistingVersionQueryable() throws Exception {
    createActiveTableFixture();

    // Step 1: Insert to create version
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    // Step 2: Get version number
    AggregatedHttpResponse versionList =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/list", "{}");
    long version = json(versionList).path("versions").get(0).path("version").asLong();

    // Step 3: Create tag pointing to version
    AggregatedHttpResponse createTagResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"e2e-tag\",\"version\":" + version + "}");

    assertSuccess(createTagResponse);
    assertThat(json(createTagResponse).path("tag_name").asText()).isEqualTo("e2e-tag");

    // Step 4: Query tag via get
    AggregatedHttpResponse getTagResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/get", "{\"tag_name\":\"e2e-tag\"}");

    assertSuccess(getTagResponse);
    assertThat(json(getTagResponse).path("version").asLong()).isEqualTo(version);

    // Step 5: Query tag via alias
    AggregatedHttpResponse aliasResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/get-version", "{\"tag_name\":\"e2e-tag\"}");

    assertSuccess(aliasResponse);
    assertThat(json(aliasResponse).path("version").asLong()).isEqualTo(version);
  }

  @Test
  @DisplayName("P3-E2E-002-02: Update tag to new version, version updated")
  void updateTagToNewVersion() throws Exception {
    createActiveTableFixture();

    // Sync two versions directly
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"create\"}"));
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":2,\"operation\":\"update\"}"));

    AggregatedHttpResponse versionList =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/list", "{}");
    JsonNode versions = json(versionList).path("versions");
    assertThat(versions.size()).isGreaterThanOrEqualTo(2);
    long v1 = versions.get(0).path("version").asLong();
    long v2 = versions.get(1).path("version").asLong();

    // Create tag on v2
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"movable-tag\",\"version\":" + v2 + "}"));

    // Update tag to v1 (latest)
    AggregatedHttpResponse updateResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/update",
            "{\"tag_name\":\"movable-tag\",\"new_version\":" + v1 + "}");

    assertSuccess(updateResponse);
    assertThat(json(updateResponse).path("version").asLong()).isEqualTo(v1);
  }

  @Test
  @DisplayName("P3-E2E-002-03: Delete tag, tag no longer queryable")
  void deleteTagNoLongerQueryable() throws Exception {
    createActiveTableFixture();

    // Create version and tag
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    AggregatedHttpResponse versionList =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/list", "{}");
    long version = json(versionList).path("versions").get(0).path("version").asLong();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"to-delete\",\"version\":" + version + "}"));

    // Delete tag
    AggregatedHttpResponse deleteResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/delete", "{\"tag_name\":\"to-delete\"}");

    assertSuccess(deleteResponse);
    assertThat(json(deleteResponse).path("deleted").asBoolean()).isTrue();

    // Verify tag not found
    AggregatedHttpResponse getResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/get", "{\"tag_name\":\"to-delete\"}");

    assertLanceErrorShape(getResponse, 404);
  }

  // ========== E2E-003: Index Sync → Query Flow ==========

  @Test
  @DisplayName("P3-E2E-003-01: Sync index, index queryable")
  void syncIndexIndexQueryable() throws Exception {
    createActiveTableFixture();

    // Step 1: Sync index metadata
    AggregatedHttpResponse syncResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"e2e-vector-idx\",\"index_type\":\"vector\","
                + "\"target_columns\":[\"embedding\"],\"distance_type\":\"cosine\","
                + "\"build_params\":{\"nlist\":128},\"status\":\"READY\"}");

    assertSuccess(syncResponse);

    // Step 2: Query index list
    AggregatedHttpResponse listResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/list", "{}");

    assertSuccess(listResponse);
    JsonNode indices = json(listResponse).path("indices");
    assertThat(indices.isArray()).isTrue();
    assertThat(indices.size()).isGreaterThanOrEqualTo(1);

    // Step 3: Query specific index
    AggregatedHttpResponse describeResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/describe",
            "{\"index_name\":\"e2e-vector-idx\"}");

    assertSuccess(describeResponse);
    assertThat(json(describeResponse).path("index_type").asText()).isEqualTo("vector");
    assertThat(json(describeResponse).path("distance_type").asText()).isEqualTo("cosine");
    assertThat(json(describeResponse).path("status").asText()).isEqualTo("READY");
  }

  @Test
  @DisplayName("P3-E2E-003-02: Sync multiple indices, all queryable")
  void syncMultipleIndicesAllQueryable() throws Exception {
    createActiveTableFixture();

    // Sync vector index
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"vector-idx\",\"index_type\":\"vector\","
                + "\"target_columns\":[\"vec\"],\"distance_type\":\"l2\"}"));

    // Sync scalar index
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"scalar-idx\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"id\"]}"));

    // Query all
    AggregatedHttpResponse listResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/list", "{}");

    assertSuccess(listResponse);
    assertThat(json(listResponse).path("indices").size()).isGreaterThanOrEqualTo(2);
  }

  // ========== E2E-004: Transaction Sync → Query Flow ==========

  @Test
  @DisplayName("P3-E2E-004-01: Transaction lifecycle sync and query")
  void transactionLifecycleSyncAndQuery() throws Exception {
    createActiveTableFixture();

    // Step 1: Start transaction (QUEUED)
    AggregatedHttpResponse queuedResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"e2e-tx-001\",\"status\":\"QUEUED\"}");

    assertSuccess(queuedResponse);
    assertThat(json(queuedResponse).path("status").asText()).isEqualTo("QUEUED");

    // Step 2: Transaction running
    AggregatedHttpResponse runningResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"e2e-tx-001\",\"status\":\"RUNNING\"}");

    assertSuccess(runningResponse);
    assertThat(json(runningResponse).path("status").asText()).isEqualTo("RUNNING");

    // Step 3: Transaction succeeded
    AggregatedHttpResponse succeededResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"e2e-tx-001\",\"status\":\"SUCCEEDED\","
                + "\"commit_metadata\":{\"rows_written\":500}}");

    assertSuccess(succeededResponse);
    assertThat(json(succeededResponse).path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(json(succeededResponse).path("commit_metadata").isObject()).isTrue();

    // Step 4: Query transaction
    AggregatedHttpResponse describeResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/transaction/describe",
            "{\"transaction_key\":\"e2e-tx-001\"}");

    assertSuccess(describeResponse);
    assertThat(json(describeResponse).path("status").asText()).isEqualTo("SUCCEEDED");
  }

  @Test
  @DisplayName("P3-E2E-004-02: Failed transaction sync and query")
  void failedTransactionSyncAndQuery() throws Exception {
    createActiveTableFixture();

    // Start and fail
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"e2e-tx-failed\",\"status\":\"RUNNING\"}"));

    AggregatedHttpResponse failedResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"e2e-tx-failed\",\"status\":\"FAILED\","
                + "\"commit_metadata\":{\"error\":\"write conflict\"}}");

    assertSuccess(failedResponse);
    assertThat(json(failedResponse).path("status").asText()).isEqualTo("FAILED");

    // Query
    AggregatedHttpResponse describeResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/transaction/describe",
            "{\"transaction_key\":\"e2e-tx-failed\"}");

    assertSuccess(describeResponse);
    assertThat(json(describeResponse).path("status").asText()).isEqualTo("FAILED");
  }

  // ========== E2E-005: Schema Sync → Query Flow ==========

  @Test
  @DisplayName("P3-E2E-005-01: Schema evolution sync and query")
  void schemaEvolutionSyncAndQuery() throws Exception {
    createActiveTableFixture();

    // Sync schema with new columns
    AggregatedHttpResponse syncResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/schema",
            "{\"version\":1,\"schema\":{\"fields\":"
                + "[{\"name\":\"id\",\"type\":\"int64\"},"
                + "{\"name\":\"embedding\",\"type\":\"fixed_size_list<float,128>\"}]},"
                + "\"operation\":\"add_columns\",\"columns_added\":[{\"name\":\"embedding\"}]}");

    assertSuccess(syncResponse);

    // Verify schema via describe (if available) or subsequent operations
    // Schema should be persisted in uc_lance_tables.arrow_schema_json
  }

  // ========== E2E-006: Cross-Table Metadata Consistency ==========

  @Test
  @DisplayName("P3-E2E-006-01: Version and Tag consistency")
  void versionAndTagConsistency() throws Exception {
    createActiveTableFixture();

    // Insert to create version
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    // Get version
    AggregatedHttpResponse versionList =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/list", "{}");
    long version = json(versionList).path("versions").get(0).path("version").asLong();

    // Create tag
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"consistency-tag\",\"version\":" + version + "}"));

    // Verify tag points to same version
    AggregatedHttpResponse tagGet =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/get", "{\"tag_name\":\"consistency-tag\"}");

    assertThat(json(tagGet).path("version").asLong()).isEqualTo(version);

    // Describe version should match
    AggregatedHttpResponse versionDescribe =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/describe",
            "{\"version\":" + version + "}");

    assertSuccess(versionDescribe);
  }

  // ========== E2E-007: Multiple Operations Sequence ==========

  @Test
  @DisplayName("P3-E2E-007-01: Insert → Tag → Index → Query sequence")
  void insertTagIndexQuerySequence() throws Exception {
    createActiveTableFixture();

    // 1. Insert data
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    // 2. Get version and create tag
    AggregatedHttpResponse versionList =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/list", "{}");
    long version = json(versionList).path("versions").get(0).path("version").asLong();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"seq-tag\",\"version\":" + version + "}"));

    // 3. Sync index
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"seq-idx\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"id\"]}"));

    // 4. Query all metadata
    AggregatedHttpResponse allVersions =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/list", "{}");
    assertSuccess(allVersions);
    assertThat(json(allVersions).path("versions").size()).isGreaterThanOrEqualTo(1);

    AggregatedHttpResponse allTags =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/list", "{}");
    assertSuccess(allTags);
    assertThat(json(allTags).path("tags").size()).isGreaterThanOrEqualTo(1);

    AggregatedHttpResponse allIndices =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/list", "{}");
    assertSuccess(allIndices);
    assertThat(json(allIndices).path("indices").size()).isGreaterThanOrEqualTo(1);
  }

  // ========== E2E-008: Upsert Semantics ==========

  @Test
  @DisplayName("P3-E2E-008-01: Version upsert preserves all fields")
  void versionUpsertPreservesFields() throws Exception {
    createActiveTableFixture();

    // First sync with partial fields
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":100,\"operation\":\"create\","
                + "\"manifest_path\":\"s3://bucket/100.manifest\"}"));

    // Second sync with more fields (upsert)
    AggregatedHttpResponse updateResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":100,\"operation\":\"update\","
                + "\"manifest_size\":2048,"
                + "\"stats\":{\"rows\":500}}");

    assertSuccess(updateResponse);
    assertThat(json(updateResponse).path("version").asLong()).isEqualTo(100L);
    assertThat(json(updateResponse).path("stats").isObject()).isTrue();

    // Query to verify
    AggregatedHttpResponse describeResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/describe", "{\"version\":100}");

    assertSuccess(describeResponse);
  }

  @Test
  @DisplayName("P3-E2E-008-02: Index upsert updates status")
  void indexUpsertUpdatesStatus() throws Exception {
    createActiveTableFixture();

    // Create with BUILDING status
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"status-idx\",\"index_type\":\"vector\","
                + "\"target_columns\":[\"vec\"],\"status\":\"BUILDING\"}"));

    // Update to READY
    AggregatedHttpResponse updateResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"status-idx\",\"index_type\":\"vector\","
                + "\"target_columns\":[\"vec\"],\"status\":\"READY\","
                + "\"stats\":{\"indexed_rows\":1000}}");

    assertSuccess(updateResponse);
    assertThat(json(updateResponse).path("status").asText()).isEqualTo("READY");

    // Query
    AggregatedHttpResponse describeResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/describe",
            "{\"index_name\":\"status-idx\"}");

    assertThat(json(describeResponse).path("status").asText()).isEqualTo("READY");
  }

  // ========== E2E-009: Error Recovery ==========

  @Test
  @DisplayName("P3-E2E-009-01: Tag creation on non-existent version fails")
  void tagCreationOnNonExistentVersionFails() throws Exception {
    createActiveTableFixture();

    // No insert, no version
    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"fail-tag\",\"version\":999}");

    assertLanceErrorShape(response, 404);
    assertThat(json(response).path("message").asText()).contains("version not found");
  }

  @Test
  @DisplayName("P3-E2E-009-02: Duplicate tag creation fails")
  void duplicateTagCreationFails() throws Exception {
    createActiveTableFixture();

    // Create version and first tag
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    AggregatedHttpResponse versionList =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/list", "{}");
    long version = json(versionList).path("versions").get(0).path("version").asLong();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"dup-tag\",\"version\":" + version + "}"));

    // Attempt duplicate
    AggregatedHttpResponse duplicateResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"dup-tag\",\"version\":" + version + "}");

    assertLanceErrorShape(duplicateResponse, 409);
    assertThat(json(duplicateResponse).path("message").asText()).contains("already exists");
  }

  // ========== E2E-010: Idempotency Headers ==========

  @Test
  @DisplayName("P3-E2E-010-01: Same idempotency key, same result")
  void sameIdempotencyKeySameResult() throws Exception {
    createActiveTableFixture();

    String idempotencyKey = "e2e-idempotent-12345";

    // First call with idempotency key
    AggregatedHttpResponse firstResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":200,\"operation\":\"create\"}",
            Map.of("idempotency-key", idempotencyKey));

    assertSuccess(firstResponse);

    // Second call with same key (should succeed, same result)
    AggregatedHttpResponse secondResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":200,\"operation\":\"create\"}",
            Map.of("idempotency-key", idempotencyKey));

    assertSuccess(secondResponse);
    assertThat(json(secondResponse).path("version").asLong())
        .isEqualTo(json(firstResponse).path("version").asLong());
  }

  // ========== E2E-011: Pagination ==========

  @Test
  @DisplayName("P3-E2E-011-01: Version pagination works")
  void versionPaginationWorks() throws Exception {
    createActiveTableFixture();

    // Create many versions
    for (int i = 0; i < 5; i++) {
      assertSuccess(
          postJson(
              "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
              "{\"version\":" + (100 + i) + ",\"operation\":\"insert\"}"));
    }

    // Paginated query
    AggregatedHttpResponse page1 =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/list", "{\"page_size\":2}");

    assertSuccess(page1);
    assertThat(json(page1).path("versions").size()).isEqualTo(2);
    assertThat(json(page1).path("next_page_token").asText()).isNotEmpty();
  }

  // ========== E2E-012: Metadata Filter ==========

  @Test
  @DisplayName("P3-E2E-012-01: Index list filters by status")
  void indexListFiltersByStatus() throws Exception {
    createActiveTableFixture();

    // Create with different statuses
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"ready-idx\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"id\"],\"status\":\"READY\"}"));

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"building-idx\",\"index_type\":\"vector\","
                + "\"target_columns\":[\"vec\"],\"status\":\"BUILDING\"}"));

    // Filter by READY
    AggregatedHttpResponse readyOnly =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/index/list", "{\"status\":\"READY\"}");

    assertSuccess(readyOnly);
    assertThat(json(readyOnly).path("indices").size()).isEqualTo(1);
    assertThat(json(readyOnly).path("indices").get(0).path("index_name").asText())
        .isEqualTo("ready-idx");
  }

  @Test
  @DisplayName("P3-E2E-012-02: Transaction list filters by status")
  void transactionListFiltersByStatus() throws Exception {
    createActiveTableFixture();

    // Create with different statuses
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"succeeded-tx\",\"status\":\"SUCCEEDED\"}"));

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"failed-tx\",\"status\":\"FAILED\"}"));

    // Filter by SUCCEEDED
    AggregatedHttpResponse succeededOnly =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/transaction/list", "{\"status\":\"SUCCEEDED\"}");

    assertSuccess(succeededOnly);
    assertThat(json(succeededOnly).path("transactions").size()).isEqualTo(1);
    assertThat(json(succeededOnly).path("transactions").get(0).path("transaction_key").asText())
        .isEqualTo("succeeded-tx");
  }

  // ========== E2E-013: Cross-Phase Regression ==========

  @Test
  @DisplayName("P3-E2E-013-01: Phase 1 namespace describe still works after Phase 3")
  void phase1NamespaceDescribeWorksAfterPhase3() throws Exception {
    createActiveTableFixture();

    // Phase 3 operations
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":1,\"operation\":\"create\"}"));

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/index",
            "{\"index_name\":\"test-idx\",\"index_type\":\"scalar\","
                + "\"target_columns\":[\"id\"]}"));

    // Phase 1 namespace describe should still work
    AggregatedHttpResponse namespaceDescribe = postJson("/v1/namespace/prod/describe", "{}");
    assertSuccess(namespaceDescribe);
    assertThat(json(namespaceDescribe).path("id").asText()).isEqualTo("prod");
  }

  @Test
  @DisplayName("P3-E2E-013-02: Phase 2 data plane not affected by Phase 3")
  void phase2NotAffectedByPhase3() throws Exception {
    createActiveTableFixture();

    // Phase 3 metadata operations
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/version",
            "{\"version\":10,\"operation\":\"create\"}"));

    // Phase 2 query should still work
    AggregatedHttpResponse queryResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", "{}");

    assertSuccess(queryResponse);
    // Echo backend returns mock response
  }
}
