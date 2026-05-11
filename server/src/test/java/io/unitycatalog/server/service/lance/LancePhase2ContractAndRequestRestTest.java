package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
@Disabled("""
    Superseded by enabled tests:
    - P2-CONTRACT-006 disabled backend: LancePhase2DisabledBackendRestTest
    - P2-REQ-001~008 request mapping: LancePhase2RequestMappingRestTest
    - P2-CONTRACT-004 content type validation: LancePhase2RequestValidationRestTest
    - P2-CONTRACT-005 Arrow response: LancePhase2ArrowResponseWriterRestTest
    - P2-CONTRACT-008 UC route exclusion: LancePhase2LocalUnsupportedRestTest
    Retain for OpenAPI baseline snapshot (P2-CONTRACT-001) and route mount verification.""")
class LancePhase2ContractAndRequestRestTest extends BaseLancePhase2RestTest {

  @Test
  @DisplayName("P2-CONTRACT-001 OpenAPI data endpoint baseline snapshot is present")
  void phase2OpenApiDataEndpointBaselineSnapshotIsPresent() throws IOException {
    String schema = readPhase2OpenApiBaseline();

    for (Map.Entry<String, String> endpoint : dataEndpointMethods().entrySet()) {
      assertEndpoint(schema, endpoint.getKey(), endpoint.getValue());
    }
  }

  @Test
  @DisplayName("P2-CONTRACT-002 data route is mounted under UC Lance prefix")
  void dataRouteIsMountedUnderUcLancePrefix() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postQueryExpectingArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", basicQueryRequest());

    assertThat(response.status().code()).isNotEqualTo(404);
    assertThat(response.contentUtf8()).doesNotContain("LanceRestTableService");
  }

  @Test
  @DisplayName("P2-CONTRACT-003 data endpoints enforce POST")
  void dataEndpointsEnforcePost() throws Exception {
    createActiveTableFixture();

    for (String endpoint : dataEndpointMethods().keySet()) {
      AggregatedHttpResponse response =
          requestLance(HttpMethod.GET, endpoint.replace("{id}", P2_ACTIVE_TABLE_ID), null, null);
      assertThat(response.status().code()).as(endpoint).isIn(405, 501);
    }
  }

  @Test
  @DisplayName("P2-CONTRACT-004 data endpoints validate content type")
  void dataEndpointsValidateContentType() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse jsonToArrowEndpoint =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", "{}");
    assertThat(jsonToArrowEndpoint.status().code()).isIn(400, 415);

    AggregatedHttpResponse arrowToJsonEndpoint =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows", arrowSmallStreamFixture());
    assertThat(arrowToJsonEndpoint.status().code()).isIn(400, 415);
  }

  @Test
  @DisplayName("P2-CONTRACT-005 query response content type is Arrow IPC")
  void queryResponseContentTypeIsArrowIpc() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postQueryExpectingArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", basicQueryRequest());

    assertArrowResponse(response);
  }

  @Test
  @DisplayName("P2-CONTRACT-006 disabled backend returns UNIMPLEMENTED for data endpoints")
  void disabledBackendReturnsUnimplementedForDataEndpoints() throws Exception {
    createActiveTableFixture();

    for (String endpoint : dataEndpointMethods().keySet()) {
      AggregatedHttpResponse response =
          requestForEndpoint(endpoint.replace("{id}", P2_ACTIVE_TABLE_ID));
      assertLanceErrorShape(response, 501);
      assertThat(json(response).path("type").asText()).containsIgnoringCase("unimplemented");
    }
  }

  @Test
  @DisplayName("P2-CONTRACT-007 Phase 1 metadata routes do not enter data backend")
  void phase1MetadataRoutesDoNotEnterDataBackend() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/describe", "{}");

    assertSuccess(response);
    assertThat(response.contentUtf8()).doesNotContain("backendType");
    assertThat(response.contentUtf8()).doesNotContain("LanceExecutionBackend");
  }

  @Test
  @DisplayName("P2-CONTRACT-008 UC, Iceberg, and Delta routes are not shadowed")
  void ucIcebergAndDeltaRoutesAreNotShadowed() throws Exception {
    createUcCatalogAndSchema();

    AggregatedHttpResponse ucTables =
        getRaw("/api/2.1/unity-catalog/tables", Map.of("x-lance-tenant-id", "tenant-a"));
    AggregatedHttpResponse iceberg =
        getRaw("/api/2.1/unity-catalog/iceberg/v1/config?warehouse=" + UC_CATALOG_NAME, Map.of());
    AggregatedHttpResponse delta = getRaw("/api/2.1/unity-catalog/delta/delta/v1/config", Map.of());

    assertThat(ucTables.status().code()).isBetween(200, 499);
    assertThat(iceberg.status().code()).isBetween(200, 499);
    assertThat(delta.status().code()).isBetween(200, 499);
    assertThat(ucTables.contentUtf8() + iceberg.contentUtf8() + delta.contentUtf8())
        .doesNotContain("LanceRestTableDataService");
  }

  @Test
  @DisplayName("P2-REQ-001 path table id wins over body id")
  void pathTableIdWinsOverBodyId() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postJson(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows",
                "{\"id\":\"prod$team_a$spoofed\",\"predicate\":\"id > 0\"}"));

    assertThat(command.path("tableId").asText()).isEqualTo(P2_ACTIVE_TABLE_ID);
  }

  @Test
  @DisplayName("P2-REQ-002 delimiter maps dot identifier to canonical path key")
  void delimiterMapsDotIdentifierToCanonicalPathKey() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postJson("/v1/table/prod.team_a.embeddings/count_rows?delimiter=.", "{}"));

    assertThat(command.path("tableId").asText()).isEqualTo(P2_ACTIVE_TABLE_ID);
    assertThat(command.path("pathKey").asText()).isEqualTo(P2_ACTIVE_TABLE_ID);
  }

  @Test
  @DisplayName("P2-REQ-003 body identity cannot override authenticated principal")
  void bodyIdentityCannotOverrideAuthenticatedPrincipal() throws Exception {
    createActiveTableFixture();
    String principal = "phase2-reader@example.com";

    JsonNode command =
        assertCommandEcho(
            postJsonWithHeaders(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows",
                "{\"identity\":{\"principal\":\"evil@example.com\"}}",
                Map.of("Authorization", "Bearer " + createInternalBearerToken(principal))));

    assertThat(command.path("principal").asText()).isEqualTo(principal);
    assertThat(command.toString()).doesNotContain("evil@example.com");
  }

  @Test
  @DisplayName("P2-REQ-004 context headers merge without overwriting server fields")
  void contextHeadersMergeWithoutOverwritingServerFields() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postJsonWithHeaders(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
                "{\"context\":{\"requestId\":\"body-request\",\"authType\":\"body\"}}",
                Map.of("x-lance-tenant-id", "tenant-a", "x-request-id", "server-request")));

    assertThat(command.path("context").path("tenantId").asText()).isEqualTo("tenant-a");
    assertThat(command.path("requestId").asText()).isEqualTo("server-request");
    assertThat(command.path("authType").asText()).isNotEqualTo("body");
  }

  @Test
  @DisplayName("P2-REQ-005 request id is propagated to backend and response")
  void requestIdIsPropagatedToBackendAndResponse() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows",
            "{}",
            Map.of("x-request-id", "phase2-request-1"));
    JsonNode command = assertCommandEcho(response);

    assertThat(command.path("requestId").asText()).isEqualTo("phase2-request-1");
    assertThat(response.headers().toString()).contains("phase2-request-1");
  }

  @Test
  @DisplayName("P2-REQ-006 idempotency key is hashed before command/audit exposure")
  void idempotencyKeyIsHashedBeforeCommandAndAuditExposure() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postArrow(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
                arrowSmallStreamFixture(),
                Map.of("Idempotency-Key", "plain-idempotency-key")));

    assertThat(command.path("idempotencyKeyHash").asText()).isNotBlank();
    assertThat(command.toString()).doesNotContain("plain-idempotency-key");
  }

  @Test
  @DisplayName("P2-REQ-007 oversized JSON body returns 413")
  void oversizedJsonBodyReturns413() throws Exception {
    createActiveTableFixture();
    String oversizedBody = "{\"filter\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}";

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows", oversizedBody);

    assertLanceErrorShape(response, 413);
  }

  @Test
  @DisplayName("P2-REQ-008 invalid JSON returns INVALID_ARGUMENT")
  void invalidJsonReturnsInvalidArgument() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows", "{\"filter\":");

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("type").asText()).containsIgnoringCase("invalid");
  }

  private AggregatedHttpResponse requestForEndpoint(String endpoint) {
    if (endpoint.endsWith("/insert")
        || endpoint.endsWith("/merge_insert")
        || endpoint.endsWith("/create")) {
      return postArrow(endpoint, arrowSmallStreamFixture());
    }
    return requestLance(
        HttpMethod.POST,
        endpoint,
        MediaType.JSON,
        basicQueryRequest().getBytes(StandardCharsets.UTF_8));
  }

  private String readPhase2OpenApiBaseline() throws IOException {
    try (var inputStream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("lance/lance-rest-phase2-openapi-baseline.yml")) {
      assertThat(inputStream).as("Phase 2 Lance REST OpenAPI baseline resource").isNotNull();
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private void assertEndpoint(String schema, String path, String method) {
    int endpointStart = schema.indexOf(path + ":");
    assertThat(endpointStart).as(path).isNotNegative();

    int nextEndpointStart = schema.indexOf("\n  /", endpointStart + path.length());
    String endpointBlock =
        nextEndpointStart < 0
            ? schema.substring(endpointStart)
            : schema.substring(endpointStart, nextEndpointStart);
    assertThat(endpointBlock).as(path).contains("\n    " + method + ":");
  }

  private Map<String, String> dataEndpointMethods() {
    Map<String, String> endpoints = new LinkedHashMap<>();
    endpoints.put("/v1/table/{id}/query", "post");
    endpoints.put("/v1/table/{id}/count_rows", "post");
    endpoints.put("/v1/table/{id}/stats", "post");
    endpoints.put("/v1/table/{id}/insert", "post");
    endpoints.put("/v1/table/{id}/merge_insert", "post");
    endpoints.put("/v1/table/{id}/update", "post");
    endpoints.put("/v1/table/{id}/delete", "post");
    endpoints.put("/v1/table/{id}/explain_plan", "post");
    endpoints.put("/v1/table/{id}/analyze_plan", "post");
    endpoints.put("/v1/table/{id}/create", "post");
    return endpoints;
  }
}
