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

@Tag("lance-phase2")
class LancePhase2StorageCredentialRestTest extends BaseLancePhase2RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  @Test
  @DisplayName("P2-STORAGE-001 storage template is loaded into backend command")
  void storageTemplateIsLoadedIntoBackendCommand() throws Exception {
    createActiveTableWithStorageTemplate();

    JsonNode storage =
        assertCommandEcho(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}"))
            .path("storage");

    assertThat(storage.path("storageOptions").toString()).contains("provider", "region");
    assertThat(storage.path("storageOptionsTemplate").toString()).contains("provider", "region");
    assertThat(storage.toString()).doesNotContain("session_token", "access_key_id");
    assertThat(storage.path("vendCredentials").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("P2-STORAGE-002 runtime credentials are merged for data operations")
  void runtimeCredentialsAreMergedForDataOperations() throws Exception {
    createActiveTableWithStorageTemplate();

    JsonNode storage =
        assertCommandEcho(
                postJsonWithHeaders(
                    "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
                    "{}",
                    Map.of("x-lance-fake-runtime-credential", "temporary-token")))
            .path("storage");

    assertThat(storage.path("storageOptions").path("session_token").asText())
        .isEqualTo("temporary-token");
    assertThat(storage.path("vendCredentials").asBoolean()).isTrue();
    assertThat(storage.path("expiresAtMillis").asLong()).isGreaterThan(0L);
  }

  @Test
  @DisplayName("P2-STORAGE-003 runtime credentials override template fields")
  void runtimeCredentialsOverrideTemplateFields() throws Exception {
    createActiveTableWithStorageTemplate();

    JsonNode storage =
        assertCommandEcho(
                postJsonWithHeaders(
                    "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
                    "{}",
                    Map.of("x-lance-fake-runtime-region", "us-east-1")))
            .path("storage");

    assertThat(storage.path("storageOptions").path("region").asText()).isEqualTo("us-east-1");
    assertThat(storage.path("storageOptionsTemplate").path("region").asText())
        .isEqualTo("us-west-2");
  }

  @Test
  @DisplayName("P2-STORAGE-004 scalar client responses are redacted")
  void scalarClientResponsesAreRedacted() throws Exception {
    createActiveTableWithStorageTemplate();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows",
            "{}",
            Map.of("x-lance-fake-runtime-credential", "secret-token"));

    assertSuccess(response);
    assertThat(response.contentUtf8()).doesNotContain("secret-token", "session_token");
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

    assertLanceErrorShape(response, 403);
    assertThat(response.contentUtf8()).contains("Runtime storage credentials expired");
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

    JsonNode storage =
        assertCommandEcho(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}"))
            .path("storage");

    assertThat(storage.path("uri").asText()).startsWith("file:");
    assertThat(storage.path("storageOptions").toString()).doesNotContain("session_token");
    assertThat(storage.path("vendCredentials").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("P2-META-008 runtime credentials are not persisted")
  void runtimeCredentialsAreNotPersisted() throws Exception {
    createActiveTableWithStorageTemplate();

    assertSuccess(
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows",
            "{}",
            Map.of("x-lance-fake-runtime-credential", "secret-session-token")));

    AggregatedHttpResponse describe =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/describe", "{\"vend_credentials\":false}");
    assertSuccess(describe);
    assertThat(describe.contentUtf8()).doesNotContain("secret", "session", "token", "expires");
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
