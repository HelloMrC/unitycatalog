package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.persist.LanceTableRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.service.lance.backend.LanceMetadataFailureExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2BackendCommittedFailureRestTest extends BaseLancePhase2RestTest {
  private final LanceIdentifierCodec identifierCodec = new LanceIdentifierCodec();
  private LanceTableRepository tableRepository;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceMetadataFailureExecutionBackend.class.getName());
  }

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    Repositories repositories =
        new Repositories(
            hibernateConfigurator.getSessionFactory(), new ServerProperties(serverProperties));
    tableRepository = repositories.getLanceTableRepository();
  }

  @Test
  @DisplayName("Phase 2 backend committed metadata failure is explicit")
  void backendCommittedMetadataFailureIsExplicit() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertThat(response.status().code()).isEqualTo(500);
    JsonNode body = json(response);
    assertThat(body.path("type").asText()).isEqualTo("metadata_update_failed");
    assertThat(body.path("backend_committed").asBoolean()).isTrue();
    assertThat(body.path("reconcileRequired").asBoolean()).isTrue();
    assertThat(body.path("message").asText()).contains("metadata update failed");
    assertThat(table(P2_ACTIVE_TABLE_ID).getCurrentVersion()).isNull();
  }

  private LanceTableDAO table(String identifier) {
    LanceAssetDAO asset =
        tableRepository.findAssetByPathKey(tablePathKey(identifier)).orElseThrow();
    return tableRepository.findTableByAssetId(asset.getId()).orElseThrow();
  }

  private String tablePathKey(String identifier) {
    return identifierCodec.toPathKey(identifierCodec.decodeIdentifier(identifier, null));
  }
}
