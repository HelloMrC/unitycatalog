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

  @Test
  @DisplayName("P2-WORKER-E2E-002 worker-http write endpoints update active table metadata")
  void workerHttpWriteEndpointsUpdateActiveTableMetadata() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse insert =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            "active-insert-row".getBytes(StandardCharsets.UTF_8));
    assertSuccess(insert);
    assertThat(json(insert).path("version").asLong()).isEqualTo(1L);
    assertThat(json(insert).path("stats").path("numRows").asLong()).isEqualTo(1L);
    assertExecutionMetadata(P2_ACTIVE_TABLE_ID, 1L, 1L);

    AggregatedHttpResponse mergeInsert =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/merge_insert",
            "active-merge-row".getBytes(StandardCharsets.UTF_8));
    assertSuccess(mergeInsert);
    assertThat(json(mergeInsert).path("version").asLong()).isEqualTo(2L);
    assertThat(json(mergeInsert).path("insertedRows").asLong()).isEqualTo(1L);
    assertThat(json(mergeInsert).path("stats").path("numRows").asLong()).isEqualTo(2L);
    assertExecutionMetadata(P2_ACTIVE_TABLE_ID, 2L, 2L);

    AggregatedHttpResponse update =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/update", "{\"predicate\":\"id = 1\"}");
    assertSuccess(update);
    assertThat(json(update).path("version").asLong()).isEqualTo(3L);
    assertThat(json(update).path("updatedRows").asLong()).isEqualTo(1L);
    assertThat(json(update).path("stats").path("numRows").asLong()).isEqualTo(2L);
    assertExecutionMetadata(P2_ACTIVE_TABLE_ID, 3L, 2L);

    AggregatedHttpResponse delete =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/delete", "{\"predicate\":\"id >= 0\"}");
    assertSuccess(delete);
    assertThat(json(delete).path("version").asLong()).isEqualTo(4L);
    assertThat(json(delete).path("deletedRows").asLong()).isEqualTo(2L);
    assertThat(json(delete).path("stats").path("numRows").asLong()).isEqualTo(0L);
    assertExecutionMetadata(P2_ACTIVE_TABLE_ID, 4L, 0L);

    AggregatedHttpResponse count =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows", "{}");
    assertSuccess(count);
    assertThat(json(count).asLong()).isEqualTo(0L);
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

  private void assertExecutionMetadata(String identifier, long expectedVersion, long expectedRows) {
    LanceAssetDAO asset = asset(identifier);
    LanceTableDAO table = tableRepository.findTableByAssetId(asset.getId()).orElseThrow();
    assertThat(asset.getState()).isEqualTo("ACTIVE");
    assertThat(table.getIsOnlyDeclared()).isFalse();
    assertThat(table.getCurrentVersion()).isEqualTo(expectedVersion);
    assertThat(table.getStatsJson()).contains("\"numRows\":" + expectedRows);
  }

  private LanceAssetDAO asset(String identifier) {
    return tableRepository.findAssetByPathKey(tablePathKey(identifier)).orElseThrow();
  }

  private String tablePathKey(String identifier) {
    return identifierCodec.toPathKey(identifierCodec.decodeIdentifier(identifier, null));
  }
}
