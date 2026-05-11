package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.persist.LanceApiKeyRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.utils.ServerProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
@Disabled("""
    Superseded by enabled tests:
    - P2-AUTH-005~008 read/write allow-deny: LancePhase2DataPlaneAuthorizationRestTest
    - P2-AUTH-011~014 audit/metrics: LancePhase2ObservabilityRestTest
    - P2-AUTH-016/017 token exchange loopback: LancePhase2LocalUnsupportedRestTest
    Retain for Bearer/API key granular tests and LanceApiKeyRepository integration.""")
class LancePhase2AuthGovernanceRestTest extends BaseLancePhase2RestTest {
  private LanceApiKeyRepository lanceApiKeyRepository;

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    Repositories repositories =
        new Repositories(
            hibernateConfigurator.getSessionFactory(), new ServerProperties(serverProperties));
    lanceApiKeyRepository = repositories.getLanceApiKeyRepository();
  }

  @Test
  @DisplayName("P2-AUTH-001 internal Bearer principal enters data command")
  void internalBearerPrincipalEntersDataCommand() throws Exception {
    createActiveTableFixture();
    String principal = "phase2-reader@example.com";

    JsonNode command =
        assertCommandEcho(
            postJsonWithHeaders(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows",
                "{}",
                Map.of("Authorization", "Bearer " + createInternalBearerToken(principal))));

    assertThat(command.path("principal").asText()).isEqualTo(principal);
    assertThat(command.path("authType").asText()).containsIgnoringCase("bearer");
  }

  @Test
  @DisplayName("P2-AUTH-002 external Bearer is exchanged before data command")
  void externalBearerIsExchangedBeforeDataCommand() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postJsonWithHeaders(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows",
                "{}",
                Map.of(
                    "Authorization", "Bearer phase2-external-token",
                    "x-lance-test-external-principal", "phase2-external@example.com")));

    assertThat(command.path("principal").asText()).isEqualTo("phase2-external@example.com");
    assertThat(command.path("authType").asText()).containsIgnoringCase("external");
  }

  @Test
  @DisplayName("P2-AUTH-003 x-api-key resolves service principal for data writes")
  void apiKeyResolvesServicePrincipalForDataWrites() throws Exception {
    createActiveTableFixture();
    lanceApiKeyRepository.createApiKey(
        "phase2-service-key",
        "phase2-service",
        "SERVICE_PRINCIPAL",
        LanceApiKeyRepository.ACTIVE_STATUS,
        "admin",
        null,
        null);

    JsonNode command =
        assertCommandEcho(
            postArrow(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
                arrowSmallStreamFixture(),
                Map.of("x-api-key", "phase2-service-key")));

    assertThat(command.path("principal").asText()).isEqualTo("phase2-service");
    assertThat(command.path("authType").asText()).containsIgnoringCase("api");
  }

  @Test
  @DisplayName("P2-AUTH-004 unauthenticated Arrow request is rejected before backend")
  void unauthenticatedArrowRequestIsRejectedBeforeBackend() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("x-lance-test-require-auth", "true"));

    assertLanceErrorShape(response, 401);
    assertThat(response.contentUtf8()).doesNotContain("backendRequestId");
  }

  @Test
  @DisplayName("P2-AUTH-005 READ_DATA allows read endpoints")
  void readDataAllowsReadEndpoints() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows",
            "{}",
            Map.of("Authorization", "Bearer " + createInternalBearerToken("phase2-reader")));

    assertSuccess(response);
  }

  @Test
  @DisplayName("P2-AUTH-006 READ_DATA deny returns 403")
  void readDataDenyReturns403() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows",
            "{}",
            Map.of("Authorization", "Bearer " + createInternalBearerToken("phase2-deny")));

    assertLanceErrorShape(response, 403);
  }

  @Test
  @DisplayName("P2-AUTH-007 WRITE_DATA allows write endpoints")
  void writeDataAllowsWriteEndpoints() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("Authorization", "Bearer " + createInternalBearerToken("phase2-writer")));

    assertSuccess(response);
  }

  @Test
  @DisplayName("P2-AUTH-008 WRITE_DATA deny returns 403")
  void writeDataDenyReturns403() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("Authorization", "Bearer " + createInternalBearerToken("phase2-reader")));

    assertLanceErrorShape(response, 403);
  }

  @Test
  @DisplayName("P2-AUTH-009 legacy UC privileges map clearly to READ_DATA and WRITE_DATA")
  void legacyUcPrivilegesMapClearlyToReadDataAndWriteData() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postJsonWithHeaders(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows",
                "{}",
                Map.of("x-lance-test-compatible-privilege", "SELECT")));

    assertThat(command.path("requiredPrivilege").asText()).isIn("READ_DATA", "SELECT");
  }

  @Test
  @DisplayName("P2-AUTH-010 x-lance context headers enter command and audit")
  void lanceContextHeadersEnterCommandAndAudit() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postJsonWithHeaders(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
                "{}",
                Map.of("x-lance-tenant-id", "tenant-a", "x-lance-workspace-id", "workspace-a")));

    assertThat(command.path("context").toString()).contains("tenant-a", "workspace-a");
  }

  @Test
  @DisplayName("P2-AUTH-011 successful data operations are audited")
  void successfulDataOperationsAreAudited() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertSuccess(response);
    assertThat(json(response).path("audit").toString())
        .contains("operation", "backendType", "version", "bytes", "rows");
  }

  @Test
  @DisplayName("P2-AUTH-012 failed backend operations are audited")
  void failedBackendOperationsAreAudited() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-worker-error", "timeout"));

    assertThat(response.status().code()).isIn(503, 504);
    assertThat(json(response).path("audit").toString())
        .contains("status", "errorCode", "backendRequestId");
  }

  @Test
  @DisplayName("P2-AUTH-013 audit and logs redact storage credentials")
  void auditAndLogsRedactStorageCredentials() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-runtime-credential", "secret-token"));

    assertSuccess(response);
    assertThat(json(response).path("audit").toString()).doesNotContain("secret-token", "session");
  }

  @Test
  @DisplayName("P2-AUTH-014 metrics expose operation status latency and backend labels")
  void metricsExposeOperationStatusLatencyAndBackendLabels() throws Exception {
    createActiveTableFixture();
    assertSuccess(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}"));

    AggregatedHttpResponse metrics = getRaw("/metrics", Map.of());

    assertThat(metrics.status().code()).isBetween(200, 499);
    assertThat(metrics.contentUtf8())
        .contains("lance")
        .contains("operation")
        .contains("status")
        .contains("backend");
  }

  @Test
  @DisplayName("P2-AUTH-015 KeyMapper delegates Lance resources without breaking UC resources")
  void keyMapperDelegatesLanceResourcesWithoutBreakingUcResources() throws Exception {
    createActiveTableFixture();
    createUcCatalogAndSchema();
    createUcRegressionTable();

    JsonNode command =
        assertCommandEcho(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}"));
    AggregatedHttpResponse ucTable =
        getRaw("/api/2.1/unity-catalog/tables/" + UC_TABLE_FULL_NAME, Map.of());

    assertThat(command.path("resourceKey").asText()).contains("lance");
    assertThat(ucTable.status().code()).isBetween(200, 499);
    assertThat(ucTable.contentUtf8()).doesNotContain("LanceResourceKeyMapper");
  }

  @Test
  @DisplayName("P2-AUTH-016 token exchange does not use internal HTTP loopback")
  void tokenExchangeDoesNotUseInternalHttpLoopback() throws Exception {
    String source =
        Files.readString(
            Path.of(
                "server/src/main/java/io/unitycatalog/server/service/lance/"
                    + "LanceAuthDecorator.java"));

    assertThat(source).doesNotContain("/api/1.0/unity-control/auth/tokens");
    assertThat(source).doesNotContain("WebClient", "HttpClient", "OkHttpClient");
  }

  @Test
  @DisplayName("P2-AUTH-017 LanceAuthDecorator excludes non-Lance routes")
  void lanceAuthDecoratorExcludesNonLanceRoutes() throws Exception {
    AggregatedHttpResponse response =
        getRaw(
            "/api/2.1/unity-catalog/catalogs",
            Map.of(
                "x-api-key", "phase2-service-key",
                "x-lance-tenant-id", "tenant-a",
                "Authorization", "Bearer external-token"));

    assertThat(response.status().code()).isNotEqualTo(500);
    assertThat(response.contentUtf8()).doesNotContain("LanceAuthDecorator", "lance operation");
  }
}
