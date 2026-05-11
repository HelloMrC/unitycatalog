package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
@Disabled("""
    Superseded by enabled tests:
    - P2-META-002~005 repository: LancePhase2DataPlaneMetadataUpdateRestTest
    - P2-META-007/009/010 reconcile: LancePhase2ReconcileRestTest
    - P2-STORAGE-001~007 credential: LancePhase2StorageCredentialRestTest
    Retain for P2-META-001 advanced asset tables check and P2-STORAGE-008 S3-compatible nightly.""")
class LancePhase2MetadataAndStorageRestTest extends BaseLancePhase2RestTest {

  @Test
  @DisplayName("P2-META-001 Phase 2 does not require advanced asset tables")
  void phase2DoesNotRequireAdvancedAssetTables() {
    assertThatThrownBy(() -> Class.forName("io.unitycatalog.server.persist.dao.LanceIndexDAO"))
        .isInstanceOf(ClassNotFoundException.class);
    assertThatThrownBy(() -> Class.forName("io.unitycatalog.server.persist.dao.LanceVersionDAO"))
        .isInstanceOf(ClassNotFoundException.class);
    assertThatThrownBy(() -> Class.forName("io.unitycatalog.server.persist.dao.LanceTagDAO"))
        .isInstanceOf(ClassNotFoundException.class);
    assertThatThrownBy(
            () -> Class.forName("io.unitycatalog.server.persist.dao.LanceTransactionDAO"))
        .isInstanceOf(ClassNotFoundException.class);
  }

  @Test
  @DisplayName("P2-META-002 markTableMaterialized updates asset and table atomically")
  void markTableMaterializedUpdatesAssetAndTableAtomically() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_DECLARED_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertSuccess(response);
    JsonNode body = json(response);
    assertThat(body.path("assetState").asText()).isEqualTo("ACTIVE");
    assertThat(body.path("is_only_declared").asBoolean()).isFalse();
    assertThat(body.path("metadataTransactionId").asText()).isNotBlank();
  }

  @Test
  @DisplayName("P2-META-003 updateTableExecutionMetadata updates version schema and stats")
  void updateTableExecutionMetadataUpdatesVersionSchemaAndStats() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertSuccess(response);
    JsonNode body = json(response);
    assertThat(body.path("version").isIntegralNumber()).isTrue();
    assertThat(body.has("arrow_schema_json")).isTrue();
    assertThat(body.has("stats_json") || body.has("stats")).isTrue();
  }

  @Test
  @DisplayName("P2-META-004 null worker schema does not overwrite existing schema")
  void nullWorkerSchemaDoesNotOverwriteExistingSchema() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("x-lance-fake-schema-source", "null"));

    assertSuccess(response);
    assertThat(json(response).path("schemaSource").asText()).isEqualTo("existing");
    assertThat(json(response).path("schemaOverwritten").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("P2-META-005 stats endpoint updates stats cache")
  void statsEndpointUpdatesStatsCache() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response = postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}");

    assertSuccess(response);
    assertThat(json(response).has("stats_json") || json(response).has("stats")).isTrue();
    assertThat(json(response).path("statsCacheUpdated").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P2-META-006 metadata update failure exposes backend_committed")
  void metadataUpdateFailureExposesBackendCommitted() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("x-lance-fake-metadata-failure", "true"));

    assertThat(response.status().code()).isIn(500, 503);
    assertThat(json(response).path("backend_committed").asBoolean()).isTrue();
    assertThat(json(response).path("reconcileRequired").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P2-META-007 concurrent materialization of declared table is controlled")
  void concurrentMaterializationOfDeclaredTableIsControlled() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse first =
        postArrow("/v1/table/" + P2_DECLARED_TABLE_ID + "/insert", arrowSmallStreamFixture());
    AggregatedHttpResponse second =
        postArrow("/v1/table/" + P2_DECLARED_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertThat(first.status().code()).isBetween(200, 299);
    assertThat(second.status().code()).isIn(200, 409);
    assertThat(first.contentUtf8() + second.contentUtf8()).contains("ACTIVE");
  }

  @Test
  @DisplayName("P2-META-008 runtime credentials are not persisted")
  void runtimeCredentialsAreNotPersisted() throws Exception {
    createActiveTableWithStorageTemplate();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-runtime-credential", "secret-session-token"));

    assertSuccess(response);
    AggregatedHttpResponse describe =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/describe", "{\"vend_credentials\":false}");
    assertSuccess(describe);
    assertThat(describe.contentUtf8()).doesNotContain("secret", "session", "token", "expires");
  }

  @Test
  @DisplayName("P2-META-009 reconcile dry-run can locate backend_committed failures")
  void reconcileDryRunCanLocateBackendCommittedFailures() throws Exception {
    createActiveTableFixture();
    postArrow(
        "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
        arrowSmallStreamFixture(),
        Map.of("x-lance-fake-metadata-failure", "true"));

    AggregatedHttpResponse response =
        postJson(
            "/admin/reconcile", "{\"table_id\":\"" + P2_ACTIVE_TABLE_ID + "\",\"dry_run\":true}");

    assertSuccess(response);
    assertThat(json(response).path("plan").toString())
        .contains("current_version", "arrow_schema_json", "stats_json");
    assertThat(json(response).path("backend_committed").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P2-META-010 controlled reconcile backfills UC metadata")
  void controlledReconcileBackfillsUcMetadata() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/admin/reconcile", "{\"table_id\":\"" + P2_ACTIVE_TABLE_ID + "\",\"dry_run\":false}");

    assertSuccess(response);
    assertThat(json(response).path("status").asText()).isEqualTo("UPDATED");
    assertThat(json(response).path("audit").toString()).contains("operator", "before", "after");
  }

  @Test
  @DisplayName("P2-STORAGE-001 storage template is loaded into backend command")
  void storageTemplateIsLoadedIntoBackendCommand() throws Exception {
    createActiveTableWithStorageTemplate();

    JsonNode command =
        assertCommandEcho(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}"));

    assertThat(command.path("storage").toString()).contains("provider", "region");
    assertThat(command.path("storage").toString()).doesNotContain("session_token", "access_key_id");
  }

  @Test
  @DisplayName("P2-STORAGE-002 runtime credentials are merged for data operations")
  void runtimeCredentialsAreMergedForDataOperations() throws Exception {
    createActiveTableWithStorageTemplate();

    JsonNode command =
        assertCommandEcho(
            postJsonWithHeaders(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
                "{}",
                Map.of("x-lance-fake-runtime-credential", "temporary-token")));

    assertThat(command.path("storage").toString()).contains("temporary-token");
  }

  @Test
  @DisplayName("P2-STORAGE-003 runtime credentials override template fields")
  void runtimeCredentialsOverrideTemplateFields() throws Exception {
    createActiveTableWithStorageTemplate();

    JsonNode command =
        assertCommandEcho(
            postJsonWithHeaders(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
                "{}",
                Map.of("x-lance-fake-runtime-region", "us-east-1")));

    assertThat(command.path("storage").path("region").asText()).isEqualTo("us-east-1");
  }

  @Test
  @DisplayName("P2-STORAGE-004 client responses and audit are redacted")
  void clientResponsesAndAuditAreRedacted() throws Exception {
    createActiveTableWithStorageTemplate();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-runtime-credential", "secret-token"));

    assertSuccess(response);
    assertThat(response.contentUtf8()).doesNotContain("secret-token", "session_secret");
  }

  @Test
  @DisplayName("P2-STORAGE-005 expired credentials fail in controlled way")
  void expiredCredentialsFailInControlledWay() throws Exception {
    createActiveTableWithStorageTemplate();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-runtime-credential-expired", "true"));

    assertThat(response.status().code()).isIn(401, 403, 503);
    assertThat(response.contentUtf8()).doesNotContain("Exception stack");
  }

  @Test
  @DisplayName("P2-STORAGE-006 external location access denied returns 403")
  void externalLocationAccessDeniedReturns403() throws Exception {
    createActiveTableWithStorageTemplate();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-storage-denied", "true"));

    assertLanceErrorShape(response, 403);
  }

  @Test
  @DisplayName("P2-STORAGE-007 local FS table does not require cloud credentials")
  void localFsTableDoesNotRequireCloudCredentials() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}"));

    assertThat(command.path("storage").path("uri").asText()).startsWith("file:");
    assertThat(command.path("storage").toString()).doesNotContain("access_key", "session_token");
  }

  @Test
  @DisplayName("P2-STORAGE-008 S3-compatible storage smoke can use storage_options")
  void s3CompatibleStorageSmokeCanUseStorageOptions() throws Exception {
    createActiveTableWithStorageTemplate();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-nightly-object-store", "s3-compatible"));

    assertSuccess(response);
    assertThat(json(response).path("storageAccessVerified").asBoolean()).isTrue();
  }

  private void createActiveTableWithStorageTemplate() {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/register",
            "{"
                + "\"location\":\"s3://bucket/embeddings.lance\","
                + "\"storage_options_template\":{"
                + "\"provider\":\"s3\","
                + "\"region\":\"us-west-2\","
                + "\"endpoint\":\"http://minio:9000\","
                + "\"session_token\":\"must-not-persist\""
                + "},"
                + "\"schema\":{\"fields\":[]},"
                + "\"properties\":{\"table_type\":\"lance\"}"
                + "}"));
  }
}
