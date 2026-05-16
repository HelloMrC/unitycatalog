package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.persist.LanceTableRepository;
import io.unitycatalog.server.persist.LanceVersionRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.persist.dao.LanceVersionDAO;
import io.unitycatalog.server.service.lance.backend.LanceTestEchoExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2DataPlaneMetadataUpdateRestTest extends BaseLancePhase2RestTest {
  private final LanceIdentifierCodec identifierCodec = new LanceIdentifierCodec();
  private LanceTableRepository tableRepository;
  private LanceVersionRepository versionRepository;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    Repositories repositories =
        new Repositories(
            hibernateConfigurator.getSessionFactory(), new ServerProperties(serverProperties));
    tableRepository = repositories.getLanceTableRepository();
    versionRepository = repositories.getLanceVersionRepository();
  }

  @Test
  @DisplayName("Phase 2 declared table insert materializes metadata")
  void declaredTableInsertMaterializesMetadata() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_DECLARED_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertSuccess(response);
    LanceAssetDAO asset = asset(P2_DECLARED_TABLE_ID);
    LanceTableDAO table = tableRepository.findTableByAssetId(asset.getId()).orElseThrow();
    assertThat(asset.getState()).isEqualTo("ACTIVE");
    assertThat(table.getIsOnlyDeclared()).isFalse();
    assertThat(table.getCurrentVersion()).isEqualTo(1L);
    assertThat(table.getArrowSchemaJson()).isEqualTo("{}");
    assertThat(table.getStatsJson()).isEqualTo("{}");
    LanceVersionDAO version = version(P2_DECLARED_TABLE_ID, 1L);
    assertThat(version.getOperation()).isEqualTo("insert");
    assertThat(version.getStatsJson()).isEqualTo("{}");
  }

  @Test
  @DisplayName("Phase 2 active table write updates execution metadata")
  void activeTableWriteUpdatesExecutionMetadata() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertSuccess(response);
    LanceTableDAO table = table(P2_ACTIVE_TABLE_ID);
    assertThat(table.getCurrentVersion()).isEqualTo(1L);
    assertThat(table.getArrowSchemaJson()).isEqualTo("{}");
    assertThat(table.getStatsJson()).isEqualTo("{}");
    LanceVersionDAO version = version(P2_ACTIVE_TABLE_ID, 1L);
    assertThat(version.getOperation()).isEqualTo("insert");
    assertThat(version.getStatsJson()).isEqualTo("{}");
  }

  @Test
  @DisplayName("Phase 2 write metadata cannot move current version backwards")
  void writeMetadataCannotMoveCurrentVersionBackwards() throws Exception {
    createActiveTableFixture();
    LanceAssetDAO asset = asset(P2_ACTIVE_TABLE_ID);
    tableRepository.updateTableExecutionMetadata(
        asset.getId(), 2L, "{\"schema\":\"current\"}", "{\"numRows\":2}", "phase2-test");

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertSuccess(response);
    assertThat(json(response).path("metadataVersionUpdated").asBoolean()).isFalse();
    assertThat(json(response).path("warnings").toString()).containsIgnoringCase("version");
    LanceTableDAO table = table(P2_ACTIVE_TABLE_ID);
    assertThat(table.getCurrentVersion()).isEqualTo(2L);
    assertThat(table.getArrowSchemaJson()).isEqualTo("{\"schema\":\"current\"}");
    assertThat(table.getStatsJson()).isEqualTo("{\"numRows\":2}");
    assertThat(versionRepository.findVersion(asset.getId(), 1L)).isEmpty();
  }

  @Test
  @DisplayName("Phase 2 stats response refreshes stats cache")
  void statsResponseRefreshesStatsCache() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response = postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}");

    assertSuccess(response);
    LanceTableDAO table = table(P2_ACTIVE_TABLE_ID);
    assertThat(table.getStatsJson()).contains("\"totalBytes\":0");
    assertThat(table.getStatsJson()).contains("\"numRows\":0");
  }

  private LanceTableDAO table(String identifier) {
    LanceAssetDAO asset = asset(identifier);
    return tableRepository.findTableByAssetId(asset.getId()).orElseThrow();
  }

  private LanceVersionDAO version(String identifier, Long version) {
    LanceAssetDAO asset = asset(identifier);
    return versionRepository.findVersion(asset.getId(), version).orElseThrow();
  }

  private LanceAssetDAO asset(String identifier) {
    return tableRepository.findAssetByPathKey(tablePathKey(identifier)).orElseThrow();
  }

  private String tablePathKey(String identifier) {
    return identifierCodec.toPathKey(identifierCodec.decodeIdentifier(identifier, null));
  }
}
