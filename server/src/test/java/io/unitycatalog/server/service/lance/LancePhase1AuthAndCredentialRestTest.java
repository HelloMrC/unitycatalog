package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase1")
class LancePhase1AuthAndCredentialRestTest extends BaseLancePhase1RestTest {

  @Test
  @Disabled("Enable after Lance-specific auth context is implemented.")
  @DisplayName("P1-AUTH-001 internal Bearer principal enters Lance request context")
  void internalBearerPrincipalEntersRequestContext() throws Exception {
    AggregatedHttpResponse response =
        getLance(
            "/v1/namespace/" + ROOT_NAMESPACE + "/list",
            Map.of("Authorization", "Bearer " + serverConfig.getAuthToken()));

    assertSuccess(response);
    assertThat(json(response).path("principal").asText()).isNotBlank();
  }

  @Test
  @Disabled("Enable after Lance external Bearer token exchange is implemented.")
  @DisplayName("P1-AUTH-002 external Bearer with allowed issuer and audience is exchanged")
  void allowedExternalBearerIsExchangedThroughInternalHelper() throws Exception {
    AggregatedHttpResponse response =
        getLance(
            "/v1/namespace/" + ROOT_NAMESPACE + "/list",
            Map.of("Authorization", "Bearer phase1-external-valid-token"));

    assertSuccess(response);
    assertThat(json(response).path("principal").asText()).isNotBlank();
  }

  @Test
  @Disabled("Enable after Lance external Bearer token exchange is implemented.")
  @DisplayName("P1-AUTH-003 external Bearer with invalid issuer or audience is rejected")
  void invalidExternalBearerIsRejected() throws Exception {
    AggregatedHttpResponse response =
        getLance(
            "/v1/namespace/" + ROOT_NAMESPACE + "/list",
            Map.of("Authorization", "Bearer invalid-external-token"));

    assertLanceErrorShape(response, 401);
    assertThat(json(response).path("message").asText()).containsIgnoringCase("issuer");
  }

  @Test
  @Disabled("Enable after Lance x-api-key authentication is implemented.")
  @DisplayName("P1-AUTH-004/P1-AUTH-005 x-api-key resolves principal and rejects revoked key")
  void apiKeyResolvesPrincipalAndRejectsRevokedKey() throws Exception {
    AggregatedHttpResponse accepted =
        postJson(
            "/v1/table/" + TABLE_ID + "/describe",
            "{}",
            Map.of("x-api-key", "phase1-valid-api-key"));
    assertSuccess(accepted);
    assertThat(json(accepted).path("principal").asText()).isNotBlank();

    AggregatedHttpResponse revoked =
        postJson(
            "/v1/table/" + TABLE_ID + "/describe",
            "{}",
            Map.of("x-api-key", "phase1-revoked-api-key"));
    assertLanceErrorShape(revoked, 401);
    assertThat(json(revoked).path("message").asText()).containsIgnoringCase("revoked");
  }

  @Test
  @Disabled("Enable after Lance-specific authorization checks are implemented.")
  @DisplayName("P1-AUTH-007/P1-AUTH-008 metadata permissions allow owner and reject read-only")
  void metadataPermissionsAllowOwnerAndRejectReadOnly() throws Exception {
    assertSuccess(
        postJson(
            "/v1/namespace/" + ROOT_NAMESPACE + "/create",
            createNamespaceRequest(),
            Map.of("Authorization", "Bearer phase1-namespace-owner")));

    AggregatedHttpResponse allowed =
        postJson(
            "/v1/namespace/" + CHILD_NAMESPACE + "/create",
            createNamespaceRequest(),
            Map.of("Authorization", "Bearer phase1-namespace-owner"));
    assertSuccess(allowed);

    AggregatedHttpResponse denied =
        postJson(
            "/v1/table/" + TABLE_ID + "/declare",
            declareTableRequest(TABLE_LOCATION),
            Map.of("Authorization", "Bearer phase1-readonly-user"));
    assertLanceErrorShape(denied, 403);
    assertThat(json(denied).path("type").asText()).containsIgnoringCase("permission");
  }

  @Test
  @Disabled("Enable after LanceResourceKeyMapper is implemented.")
  @DisplayName("P1-AUTH-009/P1-AUTH-010 KeyMapper delegates Lance resources through auth graph")
  void keyMapperDelegatesLanceResourcesThroughAuthGraph() throws Exception {
    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + TABLE_ID + "/declare",
            declareTableRequest(TABLE_LOCATION),
            Map.of("Authorization", "Bearer phase1-child-table-owner"));

    assertSuccess(response);
    assertThat(json(response).path("authorization").path("mapper").asText())
        .isEqualTo("LanceResourceKeyMapper");
    assertThat(json(response).path("authorization").path("parent_graph_checked").asBoolean())
        .isTrue();
    assertThat(json(response).path("authorization").path("expanded_parent_map").asBoolean())
        .isFalse();
  }

  @Test
  @Disabled("Enable after Lance request context propagation is implemented.")
  @DisplayName("P1-AUTH-012 Lance context headers are forwarded into request context and audit")
  void lanceContextHeadersAreForwarded() throws Exception {
    AggregatedHttpResponse response =
        getLance(
            "/v1/namespace/" + ROOT_NAMESPACE + "/list",
            Map.of("x-lance-tenant-id", "tenant-a", "x-lance-request-source", "phase1-test"));

    assertSuccess(response);
    assertThat(json(response).path("context").path("x-lance-tenant-id").asText())
        .isEqualTo("tenant-a");
  }

  @Test
  @Disabled("Enable after Lance audit response metadata is implemented.")
  @DisplayName("P1-AUTH-011 declare and create-empty audit fields are distinguishable")
  void declareAndCreateEmptyAuditFieldsAreDistinguishable() throws Exception {
    createRootAndChildNamespaces();

    AggregatedHttpResponse declare =
        postJson(
            "/v1/table/" + DECLARED_TABLE_ID + "/declare",
            declareTableRequest(DECLARED_TABLE_LOCATION));
    assertSuccess(declare);
    assertThat(json(declare).path("audit").path("protocol_operation").asText())
        .isEqualTo("declare_table");
    assertThat(json(declare).path("audit").path("protocol_variant").asText()).isEqualTo("declare");
    assertThat(json(declare).path("audit").path("deprecated_alias_used").asBoolean()).isFalse();

    AggregatedHttpResponse alias =
        postJson(
            "/v1/table/prod$team_a$audit_alias/create-empty",
            declareTableRequest("file:///tmp/uc-lance/audit-alias.lance"));
    assertSuccess(alias);
    assertThat(json(alias).path("audit").path("protocol_variant").asText())
        .isEqualTo("create-empty");
    assertThat(json(alias).path("audit").path("deprecated_alias_used").asBoolean()).isTrue();
  }

  @Test
  @Disabled("Implementation review guard; enable selectively during LanceAuthDecorator review.")
  @DisplayName("P1-AUTH-014 token exchange implementation path avoids HTTP loopback")
  void tokenExchangeImplementationPathAvoidsHttpLoopback() throws Exception {
    Path authDecorator =
        Path.of(
            "server/src/main/java/io/unitycatalog/server/service/lance/LanceAuthDecorator.java");

    assertThat(authDecorator).exists();
    String source = Files.readString(authDecorator);
    assertThat(source).doesNotContain("/api/1.0/unity-control/auth/tokens");
    assertThat(source).doesNotContain("WebClient.builder", "client.execute(", "HttpClient");
    assertThat(
            source.contains("grantToken")
                || source.contains("AuthService")
                || source.contains("TokenExchange")
                || source.contains("exchangeToken"))
        .as("LanceAuthDecorator should call an internal token exchange helper/service directly.")
        .isTrue();
  }

  @Test
  @DisplayName("P1-CRED-001/P1-CRED-002 vend_credentials controls storage_options response")
  void vendCredentialsControlsStorageOptionsResponse() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION)));

    AggregatedHttpResponse withoutCredentials =
        postJson("/v1/table/" + TABLE_ID + "/describe", "{\"vend_credentials\":false}");
    assertSuccess(withoutCredentials);
    assertThat(json(withoutCredentials).has("storage_options")).isFalse();

    AggregatedHttpResponse withCredentials =
        postJson("/v1/table/" + TABLE_ID + "/describe", "{\"vend_credentials\":true}");
    assertSuccess(withCredentials);
    assertThat(json(withCredentials).path("storage_options").isObject()).isTrue();
  }

  @Test
  @DisplayName("P1-CRED-003 declared-only table can vend credentials without physical metadata")
  void declaredOnlyTableCanVendCredentialsWithoutPhysicalMetadata() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson(
            "/v1/table/" + DECLARED_TABLE_ID + "/declare",
            declareTableRequest(DECLARED_TABLE_LOCATION)));

    AggregatedHttpResponse response =
        postJson("/v1/table/" + DECLARED_TABLE_ID + "/describe", "{\"vend_credentials\":true}");
    assertSuccess(response);
    assertThat(json(response).path("is_only_declared").asBoolean()).isTrue();
    assertThat(json(response).path("storage_options").isObject()).isTrue();
    assertThat(json(response).path("physical_metadata_loaded").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("P1-CRED-008/P1-CRED-009 storage template excludes temporary secret fields")
  void storageOptionsTemplateExcludesTemporarySecretFields() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson(
            "/v1/table/" + TABLE_ID + "/register",
            "{"
                + "\"location\":\""
                + TABLE_LOCATION
                + "\","
                + "\"storage_options_template\":{\"provider\":\"s3\",\"region\":\"us-west-2\"},"
                + "\"properties\":{\"table_type\":\"lance\"}"
                + "}"));

    AggregatedHttpResponse response =
        postJson("/v1/table/" + TABLE_ID + "/describe", "{\"vend_credentials\":true}");
    assertSuccess(response);

    String persistedTemplate = json(response).path("storage_options_template").toString();
    assertThat(persistedTemplate).contains("provider", "region");
    assertThat(persistedTemplate)
        .doesNotContain("token", "session", "secret", "expires", "access_key");
  }
}
