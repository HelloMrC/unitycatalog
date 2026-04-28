package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.persist.LanceApiKeyRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase1")
class LancePhase1AuthorizationEnabledRestTest extends BaseLancePhase1RestTest {
  private LanceApiKeyRepository lanceApiKeyRepository;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(Property.AUTHORIZATION_ENABLED.getKey(), "enable");
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

  @Test
  @DisplayName("P1-AUTH Lance x-api-key remains available when UC authorization is enabled")
  void lanceApiKeyWorksWhenUcAuthorizationIsEnabled() throws Exception {
    String adminToken = createInternalBearerToken("admin");
    Map<String, String> adminHeader = Map.of("Authorization", "Bearer " + adminToken);

    assertSuccess(
        postJson(
            "/v1/namespace/" + ROOT_NAMESPACE + "/create", createNamespaceRequest(), adminHeader));
    assertSuccess(
        postJson(
            "/v1/namespace/" + CHILD_NAMESPACE + "/create", createNamespaceRequest(), adminHeader));
    assertSuccess(
        postJson(
            "/v1/table/" + TABLE_ID + "/register",
            declareTableRequest(TABLE_LOCATION),
            adminHeader));

    lanceApiKeyRepository.createApiKey(
        "phase1-admin-api-key",
        "admin",
        "USER",
        LanceApiKeyRepository.ACTIVE_STATUS,
        "admin",
        null,
        null);

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + TABLE_ID + "/describe",
            "{}",
            Map.of("x-api-key", "phase1-admin-api-key"));

    assertSuccess(response);
    assertThat(json(response).path("principal").asText()).isEqualTo("admin");
  }
}
