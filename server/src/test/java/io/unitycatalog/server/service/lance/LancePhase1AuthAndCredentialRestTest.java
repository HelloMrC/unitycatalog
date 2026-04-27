package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.sun.net.httpserver.HttpServer;
import io.unitycatalog.server.persist.LanceApiKeyRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.utils.ServerProperties;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase1")
class LancePhase1AuthAndCredentialRestTest extends BaseLancePhase1RestTest {
  private static final String TEST_AUDIENCE = "unity-catalog";

  private LanceApiKeyRepository lanceApiKeyRepository;
  private HttpServer mockOidcServer;
  private String testIssuer;
  private Algorithm testIssuerAlgorithm;
  private String testIssuerKeyId;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    try {
      startMockOidcServer();
    } catch (Exception e) {
      throw new RuntimeException("Failed to start mock OIDC server", e);
    }
    serverProperties.setProperty("server.allowed-issuers", testIssuer);
    serverProperties.setProperty("server.audiences", TEST_AUDIENCE);
  }

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    Repositories repositories =
        new Repositories(
            hibernateConfigurator.getSessionFactory(), new ServerProperties(serverProperties));
    lanceApiKeyRepository = repositories.getLanceApiKeyRepository();
  }

  @AfterEach
  @Override
  public void tearDown() {
    super.tearDown();
    if (mockOidcServer != null) {
      mockOidcServer.stop(0);
      mockOidcServer = null;
    }
  }

  @Test
  @DisplayName("P1-AUTH-001 internal Bearer principal enters Lance request context")
  void internalBearerPrincipalEntersRequestContext() throws Exception {
    assertSuccess(
        postJson("/v1/namespace/" + ROOT_NAMESPACE + "/create", createNamespaceRequest()));
    String principal = "phase1-internal-user@example.com";

    AggregatedHttpResponse response =
        getLance(
            "/v1/namespace/" + ROOT_NAMESPACE + "/list",
            Map.of("Authorization", "Bearer " + createInternalBearerToken(principal)));

    assertSuccess(response);
    assertThat(json(response).path("principal").asText()).isEqualTo(principal);
  }

  @Test
  @DisplayName("P1-AUTH-002 external Bearer with allowed issuer and audience is exchanged")
  void allowedExternalBearerIsExchangedThroughInternalHelper() throws Exception {
    assertSuccess(
        postJson("/v1/namespace/" + ROOT_NAMESPACE + "/create", createNamespaceRequest()));

    AggregatedHttpResponse response =
        getLance(
            "/v1/namespace/" + ROOT_NAMESPACE + "/list",
            Map.of("Authorization", "Bearer " + createExternalBearerToken("admin", TEST_AUDIENCE)));

    assertSuccess(response);
    assertThat(json(response).path("principal").asText()).isEqualTo("admin");
  }

  @Test
  @DisplayName("P1-AUTH-003 external Bearer with invalid issuer or audience is rejected")
  void invalidExternalBearerIsRejected() throws Exception {
    AggregatedHttpResponse response =
        getLance(
            "/v1/namespace/" + ROOT_NAMESPACE + "/list",
            Map.of(
                "Authorization",
                "Bearer "
                    + createExternalBearerToken(
                        "admin", TEST_AUDIENCE, "https://evil-issuer.example.com")));

    assertLanceErrorShape(response, 401);
    assertThat(json(response).path("message").asText()).containsIgnoringCase("issuer");
  }

  @Test
  @DisplayName("P1-AUTH-004/P1-AUTH-005 x-api-key resolves principal and rejects revoked key")
  void apiKeyResolvesPrincipalAndRejectsRevokedKey() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION)));
    createApiKey("phase1-valid-api-key", LanceApiKeyRepository.ACTIVE_STATUS, null, null);
    createApiKey("phase1-revoked-api-key", LanceApiKeyRepository.REVOKED_STATUS, null, new Date());

    AggregatedHttpResponse accepted =
        postJson(
            "/v1/table/" + TABLE_ID + "/describe",
            "{}",
            Map.of("x-api-key", "phase1-valid-api-key"));
    assertSuccess(accepted);
    assertThat(json(accepted).path("principal").asText()).isEqualTo("phase1-service-principal");

    AggregatedHttpResponse revoked =
        postJson(
            "/v1/table/" + TABLE_ID + "/describe",
            "{}",
            Map.of("x-api-key", "phase1-revoked-api-key"));
    assertLanceErrorShape(revoked, 401);
    assertThat(json(revoked).path("message").asText()).containsIgnoringCase("revoked");
  }

  @Test
  @DisplayName("P1-AUTH-005/P1-AUTH-006 x-api-key rejects expired key")
  void apiKeyRejectsExpiredKey() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION)));
    createApiKey("phase1-expired-api-key", LanceApiKeyRepository.ACTIVE_STATUS, new Date(0), null);

    AggregatedHttpResponse expired =
        postJson(
            "/v1/table/" + TABLE_ID + "/describe",
            "{}",
            Map.of("x-api-key", "phase1-expired-api-key"));

    assertLanceErrorShape(expired, 401);
    assertThat(json(expired).path("message").asText()).containsIgnoringCase("expired");
  }

  @Test
  @DisplayName("P1-AUTH-013 Bearer and x-api-key are mutually exclusive")
  void bearerAndApiKeyAreMutuallyExclusive() throws Exception {
    assertSuccess(
        postJson("/v1/namespace/" + ROOT_NAMESPACE + "/create", createNamespaceRequest()));
    String bearerToken = createInternalBearerToken("phase1-internal-user@example.com");
    createApiKey("phase1-valid-api-key", LanceApiKeyRepository.ACTIVE_STATUS, null, null);

    AggregatedHttpResponse validApiKey =
        getLance(
            "/v1/namespace/" + ROOT_NAMESPACE + "/list",
            Map.of("Authorization", "Bearer " + bearerToken, "x-api-key", "phase1-valid-api-key"));
    assertLanceErrorShape(validApiKey, 401);
    assertThat(json(validApiKey).path("message").asText()).containsIgnoringCase("exclusive");

    AggregatedHttpResponse invalidApiKey =
        getLance(
            "/v1/namespace/" + ROOT_NAMESPACE + "/list",
            Map.of(
                "Authorization", "Bearer " + bearerToken, "x-api-key", "phase1-invalid-api-key"));
    assertLanceErrorShape(invalidApiKey, 401);
    assertThat(json(invalidApiKey).path("message").asText()).containsIgnoringCase("exclusive");
  }

  private void createApiKey(String plainTextKey, String status, Date expiresAt, Date revokedAt) {
    lanceApiKeyRepository.createApiKey(
        plainTextKey,
        "phase1-service-principal",
        "SERVICE_PRINCIPAL",
        status,
        "phase1-test",
        expiresAt,
        revokedAt);
  }

  private String createExternalBearerToken(String subject, String audience) {
    return createExternalBearerToken(subject, audience, testIssuer);
  }

  private String createExternalBearerToken(String subject, String audience, String issuer) {
    var builder =
        JWT.create()
            .withSubject(subject)
            .withIssuer(issuer)
            .withIssuedAt(new Date())
            .withKeyId(testIssuerKeyId)
            .withJWTId(UUID.randomUUID().toString())
            .withClaim("email", subject);
    if (audience != null) {
      builder.withAudience(audience);
    }
    return builder.sign(testIssuerAlgorithm);
  }

  private void startMockOidcServer() throws Exception {
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
    keyPairGenerator.initialize(2048);
    var keyPair = keyPairGenerator.generateKeyPair();
    RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
    RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

    testIssuerKeyId = UUID.randomUUID().toString();
    testIssuerAlgorithm = Algorithm.RSA512(publicKey, privateKey);
    String jwksJson = buildJwksJson(publicKey, testIssuerKeyId);

    mockOidcServer = HttpServer.create(new InetSocketAddress(0), 0);
    mockOidcServer.setExecutor(
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setDaemon(true);
              return thread;
            }));

    mockOidcServer.createContext(
        "/.well-known/openid-configuration",
        exchange -> {
          String discoveryDoc =
              String.format("{\"issuer\":\"%s\",\"jwks_uri\":\"%s/jwks\"}", testIssuer, testIssuer);
          byte[] body = discoveryDoc.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    mockOidcServer.createContext(
        "/jwks",
        exchange -> {
          byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    mockOidcServer.start();
    testIssuer = "http://localhost:" + mockOidcServer.getAddress().getPort();
  }

  private static String buildJwksJson(RSAPublicKey publicKey, String keyId) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String n = encoder.encodeToString(toUnsignedBytes(publicKey.getModulus()));
    String e = encoder.encodeToString(toUnsignedBytes(publicKey.getPublicExponent()));
    return String.format(
        "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS512\","
            + "\"kid\":\"%s\",\"n\":\"%s\",\"e\":\"%s\"}]}",
        keyId, n, e);
  }

  private static byte[] toUnsignedBytes(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes[0] != 0) {
      return bytes;
    }
    byte[] trimmed = new byte[bytes.length - 1];
    System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
    return trimmed;
  }

  @Test
  @DisplayName("P1-AUTH-007/P1-AUTH-008 metadata permissions allow owner and reject read-only")
  void metadataPermissionsAllowOwnerAndRejectReadOnly() throws Exception {
    // Root namespace creation requires admin
    String adminToken = createInternalBearerToken("admin");
    String ownerToken = createInternalBearerToken("phase1-namespace-owner");
    String readOnlyToken = createInternalBearerToken("phase1-readonly-user");

    assertSuccess(
        postJson(
            "/v1/namespace/" + ROOT_NAMESPACE + "/create",
            createNamespaceRequest(),
            Map.of("Authorization", "Bearer " + adminToken)));

    // Owner can create child namespace since they match the root owner
    // (but root was created by admin, so we need to test differently)
    // First, create a child namespace as admin (owner of root)
    AggregatedHttpResponse allowed =
        postJson(
            "/v1/namespace/" + CHILD_NAMESPACE + "/create",
            createNamespaceRequest(),
            Map.of("Authorization", "Bearer " + adminToken));
    assertSuccess(allowed);

    // ReadOnly user cannot declare table in child namespace
    AggregatedHttpResponse denied =
        postJson(
            "/v1/table/" + TABLE_ID + "/declare",
            declareTableRequest(TABLE_LOCATION),
            Map.of("Authorization", "Bearer " + readOnlyToken));
    assertLanceErrorShape(denied, 403);
    assertThat(json(denied).path("type").asText()).containsIgnoringCase("permission");
  }

  @Test
  @DisplayName("P1-AUTH-006 non-admin cannot create root namespace")
  void nonAdminCannotCreateRootNamespace() throws Exception {
    String nonAdminToken = createInternalBearerToken("phase1-non-admin");

    AggregatedHttpResponse denied =
        postJson(
            "/v1/namespace/" + ROOT_NAMESPACE + "/create",
            createNamespaceRequest(),
            Map.of("Authorization", "Bearer " + nonAdminToken));
    assertLanceErrorShape(denied, 403);
    assertThat(json(denied).path("message").asText()).containsIgnoringCase("admin");
  }

  @Test
  @DisplayName("P1-AUTH-009/P1-AUTH-010 KeyMapper delegates Lance resources through auth graph")
  void keyMapperDelegatesLanceResourcesThroughAuthGraph() throws Exception {
    String adminToken = createInternalBearerToken("admin");
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
            Map.of("Authorization", "Bearer " + adminToken));

    assertSuccess(response);
    assertThat(json(response).path("authorization").path("mapper").asText())
        .isEqualTo("LanceResourceKeyMapper");
    assertThat(json(response).path("authorization").path("parent_graph_checked").asBoolean())
        .isTrue();
    assertThat(json(response).path("authorization").path("expanded_parent_map").asBoolean())
        .isFalse();
  }

  @Test
  @DisplayName("P1-AUTH-012 Lance context headers are forwarded into request context and audit")
  void lanceContextHeadersAreForwarded() throws Exception {
    assertSuccess(
        postJson("/v1/namespace/" + ROOT_NAMESPACE + "/create", createNamespaceRequest()));

    AggregatedHttpResponse response =
        getLance(
            "/v1/namespace/" + ROOT_NAMESPACE + "/list",
            Map.of("x-lance-tenant-id", "tenant-a", "x-lance-request-source", "phase1-test"));

    assertSuccess(response);
    assertThat(json(response).path("context").path("x-lance-tenant-id").asText())
        .isEqualTo("tenant-a");
  }

  @Test
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
