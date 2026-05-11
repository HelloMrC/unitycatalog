package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
@Disabled("""
    Superseded by enabled tests:
    - P2-ERROR-001~004 error shape: LancePhase2RequestValidationRestTest
    - P2-ERROR-015 requestId/backend_request_id: LancePhase2ErrorResponseContractRestTest
    - P2-ERROR-016~018 unsupported endpoints: LancePhase2LocalUnsupportedRestTest
    - P2-REG-010 UC route exclusion: LancePhase2LocalUnsupportedRestTest
    Retain for P2-REG-001~009 UC/Iceberg/Delta full regression suite.""")
class LancePhase2ErrorAndRegressionRestTest extends BaseLancePhase2RestTest {

  @Test
  @DisplayName("P2-ERROR-001 invalid identifier returns INVALID_ARGUMENT")
  void invalidIdentifierReturnsInvalidArgument() throws Exception {
    AggregatedHttpResponse response = postJson("/v1/table/root_only/query", "{}");

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("type").asText()).containsIgnoringCase("invalid");
  }

  @Test
  @DisplayName("P2-ERROR-002 invalid JSON returns INVALID_ARGUMENT")
  void invalidJsonReturnsInvalidArgument() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows", "{\"predicate\":");

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("type").asText()).containsIgnoringCase("invalid");
  }

  @Test
  @DisplayName("P2-ERROR-003 unsupported media type returns stable error")
  void unsupportedMediaTypeReturnsStableError() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response = postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", "{}");

    assertLanceErrorShape(response, 415);
  }

  @Test
  @DisplayName("P2-ERROR-004 oversized request returns 413")
  void oversizedRequestReturns413() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowLargeStreamFixture());

    assertLanceErrorShape(response, 413);
  }

  @Test
  @DisplayName("P2-ERROR-005 unauthenticated request returns 401")
  void unauthenticatedRequestReturns401() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows",
            "{}",
            Map.of("x-lance-test-require-auth", "true"));

    assertLanceErrorShape(response, 401);
  }

  @Test
  @DisplayName("P2-ERROR-006 permission denied returns 403")
  void permissionDeniedReturns403() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows",
            "{}",
            Map.of("Authorization", "Bearer " + createInternalBearerToken("phase2-deny")));

    assertLanceErrorShape(response, 403);
  }

  @Test
  @DisplayName("P2-ERROR-007 table not found returns 404")
  void tableNotFoundReturns404() throws Exception {
    AggregatedHttpResponse response = postJson("/v1/table/prod$team_a$missing/count_rows", "{}");

    assertLanceErrorShape(response, 404);
  }

  @Test
  @DisplayName("P2-ERROR-008 declared table query returns conflict")
  void declaredTableQueryReturnsConflict() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse response =
        postQueryExpectingArrow("/v1/table/" + P2_DECLARED_TABLE_ID + "/query", "{}");

    assertLanceErrorShape(response, 409);
  }

  @Test
  @DisplayName("P2-ERROR-009 legacy writes return UNIMPLEMENTED")
  void legacyWritesReturnUnimplemented() throws Exception {
    createUcCatalogAndSchema();
    createLegacyLanceTable();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_LEGACY_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertLanceErrorShape(response, 501);
  }

  @Test
  @DisplayName("P2-ERROR-010 disabled backend returns UNIMPLEMENTED")
  void disabledBackendReturnsUnimplemented() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-test-backend", "disabled"));

    assertLanceErrorShape(response, 501);
  }

  @Test
  @DisplayName("P2-ERROR-011 backend timeout returns service unavailable")
  void backendTimeoutReturnsServiceUnavailable() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-worker-error", "timeout"));

    assertThat(response.status().code()).isIn(503, 504);
  }

  @Test
  @DisplayName("P2-ERROR-012 worker conflict maps to ABORTED or CONFLICT")
  void workerConflictMapsToAbortedOrConflict() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/update",
            "{\"predicate\":\"id = 1\",\"updates\":{\"text\":\"updated\"}}",
            Map.of("x-lance-fake-worker-error", "conflict"));

    assertLanceErrorShape(response, 409);
    assertThat(json(response).path("type").asText().toLowerCase(Locale.ROOT))
        .containsAnyOf("aborted", "conflict");
  }

  @Test
  @DisplayName("P2-ERROR-013 worker internal error hides stack traces")
  void workerInternalErrorHidesStackTraces() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-worker-error", "internal"));

    assertLanceErrorShape(response, 500);
    assertThat(response.contentUtf8()).doesNotContain("Exception", "at io.unitycatalog");
  }

  @Test
  @DisplayName("P2-ERROR-014 backend committed metadata failure is explicit")
  void backendCommittedMetadataFailureIsExplicit() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("x-lance-fake-metadata-failure", "true"));

    assertThat(response.status().code()).isIn(500, 503);
    assertThat(json(response).path("backend_committed").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P2-ERROR-015 error response contains required Lance fields")
  void errorResponseContainsRequiredLanceFields() throws Exception {
    AggregatedHttpResponse response = postJson("/v1/table/prod$team_a$missing/count_rows", "{}");

    assertLanceErrorShape(response, 404);
    JsonNode body = json(response);
    assertThat(body.has("type")).isTrue();
    assertThat(body.has("message")).isTrue();
    assertThat(body.has("code")).isTrue();
    assertThat(body.has("requestId")).isTrue();
  }

  @Test
  @DisplayName("P2-ERROR-016 restore table is not supported in Phase 2")
  void restoreTableIsNotSupportedInPhase2() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/restore", "{\"version\":1}");

    assertLanceErrorShapeAllowingUnmounted(response);
  }

  @Test
  @DisplayName("P2-ERROR-017 rename table is not supported in Phase 2")
  void renameTableIsNotSupportedInPhase2() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/rename", "{\"new_id\":\"prod$team_a$new\"}");

    assertLanceErrorShapeAllowingUnmounted(response);
  }

  @Test
  @DisplayName("P2-ERROR-018 schema evolution endpoints are not supported in Phase 2")
  void schemaEvolutionEndpointsAreNotSupportedInPhase2() throws Exception {
    createActiveTableFixture();

    for (String suffix :
        new String[] {"schema_metadata/update", "add_columns", "alter_columns", "drop_columns"}) {
      AggregatedHttpResponse response =
          postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/" + suffix, "{}");
      assertLanceErrorShapeAllowingUnmounted(response);
    }
  }

  @Test
  @DisplayName("P2-REG-001 Phase 1 namespace endpoints remain healthy")
  void phase1NamespaceEndpointsRemainHealthy() throws Exception {
    createRootAndChildNamespaces();

    assertSuccess(getLance("/v1/namespace/" + ROOT_NAMESPACE + "/list"));
    assertSuccess(postJson("/v1/namespace/" + ROOT_NAMESPACE + "/describe", "{}"));
    assertSuccess(postJson("/v1/namespace/" + ROOT_NAMESPACE + "/exists", "{}"));
  }

  @Test
  @DisplayName("P2-REG-002 Phase 1 table metadata endpoints remain healthy")
  void phase1TableMetadataEndpointsRemainHealthy() throws Exception {
    createActiveTableFixture();

    assertSuccess(getLance("/v1/namespace/" + CHILD_NAMESPACE + "/table/list"));
    assertSuccess(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/describe", "{}"));
    assertSuccess(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/exists", "{}"));
  }

  @Test
  @DisplayName("P2-REG-003 legacy metadata bridge remains healthy")
  void legacyMetadataBridgeRemainsHealthy() throws Exception {
    createUcCatalogAndSchema();
    createLegacyLanceTable();

    AggregatedHttpResponse response = postJson("/v1/table/" + P2_LEGACY_TABLE_ID + "/exists", "{}");

    assertSuccess(response);
    assertThat(json(response).path("exists").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P2-REG-004 UC /tables CRUD remains unaffected")
  void ucTablesCrudRemainsUnaffected() throws Exception {
    createUcCatalogAndSchema();
    createUcRegressionTable();

    assertThat(tableOperations.getTable(UC_TABLE_FULL_NAME).getName()).isEqualTo(UC_TABLE_NAME);
    tableOperations.deleteTable(UC_TABLE_FULL_NAME);
  }

  @Test
  @DisplayName("P2-REG-005 UC /schemas remains unaffected")
  void ucSchemasRemainUnaffected() throws Exception {
    createUcCatalogAndSchema();

    assertThat(schemaOperations.getSchema(UC_CATALOG_NAME + "." + UC_SCHEMA_NAME).getName())
        .isEqualTo(UC_SCHEMA_NAME);
  }

  @Test
  @DisplayName("P2-REG-006 Iceberg REST remains unaffected")
  void icebergRestRemainsUnaffected() throws Exception {
    createUcCatalogAndSchema();

    AggregatedHttpResponse response =
        getRaw("/api/2.1/unity-catalog/iceberg/v1/config?warehouse=" + UC_CATALOG_NAME, Map.of());

    assertThat(response.status().code()).isBetween(200, 499);
    assertThat(response.contentUtf8()).doesNotContain("LanceRestTableDataService");
  }

  @Test
  @DisplayName("P2-REG-007 Delta REST remains unaffected")
  void deltaRestRemainsUnaffected() throws Exception {
    AggregatedHttpResponse response =
        getRaw("/api/2.1/unity-catalog/delta/delta/v1/config", Map.of());

    assertThat(response.status().code()).isBetween(200, 499);
    assertThat(response.contentUtf8()).doesNotContain("LanceRestTableDataService");
  }

  @Test
  @DisplayName("P2-REG-008 original AuthDecorator remains functional")
  void originalAuthDecoratorRemainsFunctional() throws Exception {
    AggregatedHttpResponse response =
        getRaw(
            "/api/2.1/unity-catalog/catalogs",
            Map.of("x-lance-tenant-id", "tenant-a", "x-api-key", "ignored"));

    assertThat(response.status().code()).isBetween(200, 499);
    assertThat(response.contentUtf8()).doesNotContain("LanceAuthDecorator");
  }

  @Test
  @DisplayName("P2-REG-009 original KeyMapper remains functional")
  void originalKeyMapperRemainsFunctional() throws Exception {
    createUcCatalogAndSchema();
    createUcRegressionTable();

    AggregatedHttpResponse response =
        getRaw("/api/2.1/unity-catalog/tables/" + UC_TABLE_FULL_NAME, Map.of());

    assertThat(response.status().code()).isBetween(200, 499);
    assertThat(response.contentUtf8()).doesNotContain("LanceResourceKeyMapper");
  }

  @Test
  @DisplayName("P2-REG-010 LanceAuthDecorator route exclusion handles Lance headers")
  void lanceAuthDecoratorRouteExclusionHandlesLanceHeaders() throws Exception {
    AggregatedHttpResponse response =
        getRaw(
            "/api/2.1/unity-catalog/catalogs",
            Map.of("x-lance-tenant-id", "tenant-a", "x-api-key", "phase2-key"));

    assertThat(response.status().code()).isBetween(200, 499);
    assertThat(response.contentUtf8()).doesNotContain("lance operation", "phase2-key");
  }
}
