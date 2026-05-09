package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import io.unitycatalog.server.persist.LanceTableRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2RealWorkerE2ERestTest extends BaseLancePhase2RestTest {
  private final LanceIdentifierCodec identifierCodec = new LanceIdentifierCodec();
  private LanceMinimalRealWorkerFixture worker;
  private LanceTableRepository tableRepository;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    worker = new LanceMinimalRealWorkerFixture();
    worker.start();
    serverProperties.setProperty(Property.LANCE_EXECUTION_BACKEND_TYPE.getKey(), "worker-http");
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_WORKER_BASE_URL.getKey(), worker.baseUrl());
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_WORKER_HEALTH_PATH.getKey(),
        LanceMinimalRealWorkerFixture.HEALTH_PATH);
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

  @AfterEach
  @Override
  public void tearDown() {
    try {
      super.tearDown();
    } finally {
      if (worker != null) {
        worker.close();
      }
    }
  }

  @Test
  @DisplayName("P2-WORKER-E2E-001 worker-http materializes declared table and reads it back")
  void workerHttpMaterializesDeclaredTableAndReadsItBack() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse insert =
        postArrow(
            "/v1/table/" + P2_DECLARED_TABLE_ID + "/insert",
            "real-worker-row".getBytes(StandardCharsets.UTF_8));

    assertSuccess(insert);
    assertThat(json(insert).path("minimalRealWorker").asBoolean()).isTrue();
    assertThat(json(insert).path("version").asLong()).isEqualTo(1L);
    assertThat(json(insert).path("stats").path("numRows").asLong()).isEqualTo(1L);
    assertThat(json(insert).path("streamPassedThrough").asBoolean()).isTrue();
    assertThat(json(insert).path("ucRequestMode").asText()).isEqualTo("streaming");
    assertThat(json(insert).path("requestBufferedBytes").asLong()).isEqualTo(0L);
    assertMaterializedMetadata(P2_DECLARED_TABLE_ID);

    AggregatedHttpResponse query =
        postJsonWithHeaders(
            "/v1/table/" + P2_DECLARED_TABLE_ID + "/query",
            "{}",
            Map.of(HttpHeaderNames.ACCEPT.toString(), ARROW_FILE.toString()));

    assertSuccess(query);
    assertThat(query.headers().get(HttpHeaderNames.CONTENT_TYPE)).contains(ARROW_FILE.toString());
    assertThat(query.contentUtf8()).contains("minimal-real-worker", "rows=1", "version=1");

    AggregatedHttpResponse count =
        postJson("/v1/table/" + P2_DECLARED_TABLE_ID + "/count_rows", "{}");
    assertSuccess(count);
    assertThat(json(count).asLong()).isEqualTo(1L);

    AggregatedHttpResponse stats = postJson("/v1/table/" + P2_DECLARED_TABLE_ID + "/stats", "{}");
    assertSuccess(stats);
    assertThat(json(stats).path("numRows").asLong()).isEqualTo(1L);
  }

  private void assertMaterializedMetadata(String identifier) {
    LanceAssetDAO asset = asset(identifier);
    LanceTableDAO table = tableRepository.findTableByAssetId(asset.getId()).orElseThrow();
    assertThat(asset.getState()).isEqualTo("ACTIVE");
    assertThat(table.getIsOnlyDeclared()).isFalse();
    assertThat(table.getCurrentVersion()).isEqualTo(1L);
    assertThat(table.getArrowSchemaJson()).isEqualTo("{\"fields\":[]}");
    assertThat(table.getStatsJson()).contains("\"numRows\":1");
  }

  private LanceAssetDAO asset(String identifier) {
    return tableRepository.findAssetByPathKey(tablePathKey(identifier)).orElseThrow();
  }

  private String tablePathKey(String identifier) {
    return identifierCodec.toPathKey(identifierCodec.decodeIdentifier(identifier, null));
  }
}
