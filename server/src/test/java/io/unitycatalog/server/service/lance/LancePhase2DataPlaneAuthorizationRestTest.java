package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.utils.TransactionManager;
import io.unitycatalog.server.service.lance.backend.LanceTestEchoExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2DataPlaneAuthorizationRestTest extends BaseLancePhase2RestTest {
  private static final String READER = "phase2-reader@example.com";
  private static final String WRITER = "phase2-writer@example.com";

  private Repositories repositories;
  private final LanceIdentifierCodec identifierCodec = new LanceIdentifierCodec();

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(Property.AUTHORIZATION_ENABLED.getKey(), "enable");
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    repositories =
        new Repositories(
            hibernateConfigurator.getSessionFactory(), new ServerProperties(serverProperties));
  }

  @Test
  @DisplayName("P2-AUTH-005 READ_DATA allows read endpoints")
  void tableOwnerCanReadThroughReadDataGate() throws Exception {
    createActiveTableFixtureAsAdmin();
    setTableOwner(READER);

    JsonNode command =
        assertCommandEcho(
            postJsonWithHeaders(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows", "{}", authHeader(READER)));

    assertThat(command.path("requiredPrivilege").asText()).isEqualTo("READ_DATA");
  }

  @Test
  @DisplayName("P2-AUTH-006 READ_DATA deny returns 403")
  void readDataDenyReturns403() throws Exception {
    createActiveTableFixtureAsAdmin();
    setTableOwner(WRITER);

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows", "{}", authHeader(READER));

    assertLanceErrorShape(response, 403);
    assertThat(response.contentUtf8()).doesNotContain("backendType", "test-echo");
  }

  @Test
  @DisplayName("P2-AUTH-007 WRITE_DATA allows write endpoints")
  void tableOwnerCanWriteThroughWriteDataGate() throws Exception {
    createActiveTableFixtureAsAdmin();
    setTableOwner(WRITER);

    JsonNode command =
        assertCommandEcho(
            postArrow(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
                arrowSmallStreamFixture(),
                authHeader(WRITER)));

    assertThat(command.path("requiredPrivilege").asText()).isEqualTo("WRITE_DATA");
  }

  @Test
  @DisplayName("P2-AUTH-008 WRITE_DATA deny returns 403")
  void readDataPrincipalCannotWriteTableData() throws Exception {
    createActiveTableFixtureAsAdmin();
    setTableOwner(WRITER);

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            authHeader(READER));

    assertLanceErrorShape(response, 403);
    assertThat(response.contentUtf8()).doesNotContain("backendType", "test-echo");
  }

  private void createActiveTableFixtureAsAdmin() {
    Map<String, String> adminHeader = authHeader("admin");
    assertSuccess(
        postJson(
            "/v1/namespace/" + ROOT_NAMESPACE + "/create", createNamespaceRequest(), adminHeader));
    assertSuccess(
        postJson(
            "/v1/namespace/" + CHILD_NAMESPACE + "/create", createNamespaceRequest(), adminHeader));
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/register",
            declareTableRequest(TABLE_LOCATION),
            adminHeader));
  }

  private void setTableOwner(String owner) {
    LanceAssetDAO assetDAO =
        repositories
            .getLanceTableRepository()
            .findAssetByPathKey(tablePathKey())
            .orElseThrow(() -> new IllegalStateException("Missing Lance test table."));
    TransactionManager.executeWithTransaction(
        hibernateConfigurator.getSessionFactory(),
        session -> {
          LanceAssetDAO attached = session.get(LanceAssetDAO.class, assetDAO.getId());
          attached.setOwner(owner);
          attached.setUpdatedBy(owner);
          attached.setUpdatedAt(new Date());
          session.merge(attached);
          return null;
        },
        "Failed to update Lance test table owner",
        false);
  }

  private String tablePathKey() {
    return identifierCodec.toPathKey(identifierCodec.decodeIdentifier(P2_ACTIVE_TABLE_ID, null));
  }

  private Map<String, String> authHeader(String principal) {
    return Map.of("Authorization", "Bearer " + createInternalBearerToken(principal));
  }
}
