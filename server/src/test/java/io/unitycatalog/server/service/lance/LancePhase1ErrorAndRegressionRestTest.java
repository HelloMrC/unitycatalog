package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase1")
class LancePhase1ErrorAndRegressionRestTest extends BaseLancePhase1RestTest {

  @Test
  @DisplayName("P1-CONTRACT-001 Lance REST OpenAPI baseline snapshot is present")
  void lanceRestOpenApiBaselineSnapshotIsPresent() throws IOException {
    // Verify the upstream Lance REST OpenAPI baseline snapshot exists
    // Baseline commit: lancedb-docs 6c0ccc001e6b
    Path upstreamOpenApi =
        Path.of(
            "/home/lei/data_ai/learning/codebase/lancedb-docs/docs/api-reference/rest/openapi.yml");
    assertThat(upstreamOpenApi).exists();

    String schema = Files.readString(upstreamOpenApi);
    // Verify Phase 1 required endpoints are defined in upstream schema
    assertThat(schema).contains("/v1/namespace/{id}/create");
    assertThat(schema).contains("/v1/namespace/{id}/list");
    assertThat(schema).contains("/v1/namespace/{id}/describe");
    assertThat(schema).contains("/v1/namespace/{id}/drop");
    assertThat(schema).contains("/v1/namespace/{id}/exists");
    assertThat(schema).contains("/v1/namespace/{id}/table/list");
    assertThat(schema).contains("/v1/table/{id}/register");
    assertThat(schema).contains("/v1/table/{id}/declare");
    assertThat(schema).contains("/v1/table/{id}/create-empty");
    assertThat(schema).contains("/v1/table/{id}/describe");
    assertThat(schema).contains("/v1/table/{id}/exists");
    assertThat(schema).contains("/v1/table/{id}/drop");
    assertThat(schema).contains("/v1/table/{id}/deregister");
  }

  @Test
  @DisplayName("P1-CONTRACT-002 namespace endpoints enforce upstream HTTP methods")
  void namespaceEndpointsEnforceHttpMethods() throws Exception {
    assertSuccess(
        postJson("/v1/namespace/" + ROOT_NAMESPACE + "/create", createNamespaceRequest()));

    AggregatedHttpResponse list = getLance("/v1/namespace/" + ROOT_NAMESPACE + "/list");
    assertSuccess(list);

    AggregatedHttpResponse describeViaGet =
        getLance("/v1/namespace/" + ROOT_NAMESPACE + "/describe");
    assertLanceErrorShape(describeViaGet, 405);
  }

  @Test
  @DisplayName("P1-CONTRACT-003 table endpoints enforce upstream HTTP methods")
  void tableEndpointsEnforceHttpMethods() throws Exception {
    createRootAndChildNamespaces();

    AggregatedHttpResponse list = getLance("/v1/namespace/" + CHILD_NAMESPACE + "/table/list");
    assertSuccess(list);

    AggregatedHttpResponse describeViaGet = getLance("/v1/table/" + TABLE_ID + "/describe");
    assertLanceErrorShape(describeViaGet, 405);
  }

  @Test
  @DisplayName("P1-CONTRACT-004 Lance route prefix is mounted under UC base path")
  void lanceRoutePrefixIsMountedUnderUcBasePath() throws Exception {
    AggregatedHttpResponse response = getLance("/v1/namespace/" + ROOT_NAMESPACE + "/exists");

    assertThat(response.status().code()).isNotEqualTo(404);
    assertThat(response.contentUtf8()).doesNotContain("No route");
  }

  @Test
  @DisplayName("P1-CONTRACT-005 Lance route does not shadow existing UC route prefix")
  void lanceRouteDoesNotShadowExistingUcRoutePrefix() throws Exception {
    AggregatedHttpResponse response = getRaw("/api/2.1/unity-catalog/catalogs", Map.of());

    assertThat(response.status().code()).isNotEqualTo(404);
    assertThat(response.contentUtf8()).doesNotContain("LanceRest");
  }

  @Test
  @DisplayName("P1-ERROR-001/P1-ERROR-002 not found errors have Lance-compatible shape")
  void notFoundErrorsHaveLanceCompatibleShape() throws Exception {
    AggregatedHttpResponse namespaceNotFound =
        postJson("/v1/namespace/missing_namespace/describe", "{}");
    assertLanceErrorShape(namespaceNotFound, 404);

    AggregatedHttpResponse tableNotFound = postJson("/v1/table/missing$table/describe", "{}");
    assertLanceErrorShape(tableNotFound, 404);
  }

  @Test
  @DisplayName("P1-ERROR-003/P1-ERROR-005/P1-ERROR-006 common error classes are distinguishable")
  void commonErrorClassesAreDistinguishable() throws Exception {
    createRootAndChildNamespaces();

    AggregatedHttpResponse duplicateNamespace =
        postJson("/v1/namespace/" + CHILD_NAMESPACE + "/create", createNamespaceRequest());
    assertLanceErrorShape(duplicateNamespace, 409);
    assertThat(json(duplicateNamespace).path("type").asText()).containsIgnoringCase("already");

    AggregatedHttpResponse invalidRequest =
        postJson("/v1/namespace/" + CHILD_NAMESPACE + "/create", "{\"properties\":");
    assertLanceErrorShape(invalidRequest, 400);
    assertThat(json(invalidRequest).path("type").asText()).containsIgnoringCase("invalid");

    assertSuccess(
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION)));
    AggregatedHttpResponse unsupported =
        postJson("/v1/table/" + TABLE_ID + "/drop", "{\"mode\":\"delete_physical_data\"}");
    assertLanceErrorShape(unsupported, 501);
    assertThat(json(unsupported).path("message").asText()).containsIgnoringCase("not supported");
  }

  @Test
  @DisplayName("P1-ERROR-004 permission denied has stable status and body")
  void permissionDeniedHasStableStatusAndBody() throws Exception {
    // Root namespace requires admin
    String adminToken = createInternalBearerToken("admin");
    String deniedToken = createInternalBearerToken("unauthorized-phase1-user");
    assertSuccess(
        postJson(
            "/v1/namespace/" + ROOT_NAMESPACE + "/create",
            createNamespaceRequest(),
            Map.of("Authorization", "Bearer " + adminToken)));
    assertSuccess(
        postJson(
            "/v1/namespace/" + CHILD_NAMESPACE + "/create",
            createNamespaceRequest(),
            Map.of("Authorization", "Bearer " + adminToken)));

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + TABLE_ID + "/declare",
            declareTableRequest(TABLE_LOCATION),
            Map.of("Authorization", "Bearer " + deniedToken));

    assertLanceErrorShape(response, 403);
    assertThat(json(response).path("type").asText()).containsIgnoringCase("permission");
  }

  @Test
  @DisplayName("P1-ERROR-008/P1-ERROR-009 error body and HTTP status are aligned")
  void errorBodyAndHttpStatusAreAligned() throws Exception {
    AggregatedHttpResponse response = postJson("/v1/table/missing$table/describe", "{}");

    assertLanceErrorShape(response, 404);
    assertThat(json(response).path("code").asInt()).isEqualTo(response.status().code());
    assertThat(json(response).path("message").asText()).doesNotContain("io.unitycatalog");
  }

  @Test
  @DisplayName("P1-REG-001 UC /tables CRUD remains unaffected")
  void ucTablesCrudRemainsUnaffected() throws Exception {
    createUcCatalogAndSchema();
    createUcRegressionTable();

    assertThat(tableOperations.getTable(UC_TABLE_FULL_NAME).getName()).isEqualTo(UC_TABLE_NAME);
    tableOperations.deleteTable(UC_TABLE_FULL_NAME);
  }

  @Test
  @DisplayName("P1-REG-002 UC /schemas remains unaffected")
  void ucSchemasRemainsUnaffected() throws Exception {
    createUcCatalogAndSchema();

    assertThat(schemaOperations.getSchema(UC_CATALOG_NAME + "." + UC_SCHEMA_NAME).getName())
        .isEqualTo(UC_SCHEMA_NAME);
  }

  @Test
  @DisplayName("P1-REG-003 Iceberg REST metadata route remains unaffected")
  void icebergRestRemainsUnaffected() throws Exception {
    createUcCatalogAndSchema();

    AggregatedHttpResponse response =
        getRaw("/api/2.1/unity-catalog/iceberg/v1/config?warehouse=" + UC_CATALOG_NAME, Map.of());

    assertThat(response.status().code()).isBetween(200, 499);
    assertThat(response.contentUtf8()).doesNotContain("LanceAuthDecorator");
  }

  @Test
  @DisplayName("P1-REG-004 Delta REST route remains unaffected")
  void deltaRestRemainsUnaffected() throws Exception {
    AggregatedHttpResponse response =
        getRaw("/api/2.1/unity-catalog/delta/delta/v1/config", Map.of());

    assertThat(response.status().code()).isBetween(200, 499);
    assertThat(response.contentUtf8()).doesNotContain("LanceAuthDecorator");
  }

  @Test
  @DisplayName("P1-REG-005/P1-REG-006 original AuthDecorator and KeyMapper remain functional")
  void originalAuthDecoratorAndKeyMapperRemainFunctional() throws Exception {
    createUcCatalogAndSchema();
    createUcRegressionTable();

    AggregatedHttpResponse response =
        getRaw(
            "/api/2.1/unity-catalog/tables/" + UC_TABLE_FULL_NAME,
            Map.of("x-lance-tenant-id", "tenant-a", "x-api-key", "ignored-by-uc-route"));

    assertThat(response.status().code()).isBetween(200, 499);
    assertThat(response.contentUtf8()).doesNotContain("LanceResourceKeyMapper");
  }

  @Test
  @DisplayName("P1-REG-007 non-Lance route ignores Lance-specific auth headers")
  void nonLanceRouteIgnoresLanceSpecificHeaders() throws Exception {
    AggregatedHttpResponse response =
        getRaw(
            "/api/2.1/unity-catalog/catalogs",
            Map.of("x-lance-tenant-id", "tenant-a", "x-api-key", "ignored"));

    assertThat(response.status().code()).isNotEqualTo(500);
    assertThat(response.contentUtf8()).doesNotContain("LanceAuthDecorator");
  }
}
