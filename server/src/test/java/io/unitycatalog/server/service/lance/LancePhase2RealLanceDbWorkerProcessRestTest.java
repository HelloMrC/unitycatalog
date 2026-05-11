package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import io.unitycatalog.server.persist.LanceTableRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2RealLanceDbWorkerProcessRestTest extends BaseLancePhase2RestTest {
  private final LanceIdentifierCodec identifierCodec = new LanceIdentifierCodec();
  private LanceRealLanceDbWorkerProcessFixture worker;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    LanceRealLanceDbWorkerProcessFixture.DependencyCheck check =
        LanceRealLanceDbWorkerProcessFixture.checkDependencies();
    assumeTrue(
        check.available(),
        () -> "real LanceDB worker dependencies unavailable: " + check.message());
    worker = new LanceRealLanceDbWorkerProcessFixture();
    worker.start(testDirectoryRoot.resolve("real-lancedb-worker"));
    serverProperties.setProperty(Property.LANCE_EXECUTION_BACKEND_TYPE.getKey(), "worker-http");
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_WORKER_BASE_URL.getKey(), worker.baseUrl());
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_WORKER_HEALTH_PATH.getKey(),
        LanceRealLanceDbWorkerProcessFixture.HEALTH_PATH);
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
  @DisplayName("P2-WORKER-E2E-003 real LanceDB worker process query count stats insert")
  void realLanceDbWorkerProcessHandlesMinimalDataPath() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse insert =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            LanceRealLanceDbWorkerProcessFixture.sampleArrowStream());

    assertThat(insert.status().code()).as(insert.contentUtf8()).isBetween(200, 299);
    long version = json(insert).path("version").asLong();
    assertThat(json(insert).path("realLanceDbWorker").asBoolean()).isTrue();
    assertThat(version).isGreaterThanOrEqualTo(1L);
    assertThat(json(insert).path("stats").path("numRows").asLong()).isEqualTo(2L);
    assertThat(json(insert).path("arrowSchemaJson").asText()).contains("id", "text");
    assertExecutionMetadata(P2_ACTIVE_TABLE_ID, version, 2L);

    AggregatedHttpResponse query =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/query",
            "{}",
            Map.of(HttpHeaderNames.ACCEPT.toString(), ARROW_FILE.toString()));
    assertThat(query.status().code()).as(query.contentUtf8()).isBetween(200, 299);
    assertThat(query.headers().get(HttpHeaderNames.CONTENT_TYPE)).contains(ARROW_FILE.toString());
    assertThat(query.content().array()).startsWith("ARROW1".getBytes());

    AggregatedHttpResponse count =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows", "{}");
    assertSuccess(count);
    assertThat(json(count).asLong()).isEqualTo(2L);

    AggregatedHttpResponse stats = postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}");
    assertSuccess(stats);
    assertThat(json(stats).path("numRows").asLong()).isEqualTo(2L);
    assertExecutionMetadata(P2_ACTIVE_TABLE_ID, version, 2L);
  }

  @Test
  @DisplayName("P2-WORKER-E2E-004 real LanceDB worker process create materializes declared table")
  void realLanceDbWorkerProcessCreateMaterializesDeclaredTable() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse create =
        postArrow(
            "/v1/table/" + P2_DECLARED_TABLE_ID + "/create",
            LanceRealLanceDbWorkerProcessFixture.sampleArrowStream());

    assertSuccess(create);
    assertThat(json(create).path("realLanceDbWorker").asBoolean()).isTrue();
    assertThat(json(create).path("transactionId").asText()).startsWith("real-lancedb-create-");
    assertThat(json(create).path("version").asLong()).isEqualTo(1L);
    assertThat(json(create).path("stats").path("numRows").asLong()).isEqualTo(2L);
    assertExecutionMetadata(P2_DECLARED_TABLE_ID, 1L, 2L);
  }

  @Test
  @DisplayName("P2-WORKER-E2E-005 real LanceDB worker process write endpoints update metadata")
  void realLanceDbWorkerProcessWriteEndpointsUpdateMetadata() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse insert =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            LanceRealLanceDbWorkerProcessFixture.sampleArrowStream());
    assertSuccess(insert);
    assertThat(json(insert).path("version").asLong()).isEqualTo(1L);
    assertThat(json(insert).path("stats").path("numRows").asLong()).isEqualTo(2L);
    assertExecutionMetadata(P2_ACTIVE_TABLE_ID, 1L, 2L);

    AggregatedHttpResponse mergeInsert =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/merge_insert",
            LanceRealLanceDbWorkerProcessFixture.sampleArrowStream(),
            Map.of("x-lance-merge-options", mergeInsertOptions()));
    assertSuccess(mergeInsert);
    assertThat(json(mergeInsert).path("version").asLong()).isEqualTo(2L);
    assertThat(json(mergeInsert).path("updatedRows").asLong()).isEqualTo(2L);
    assertThat(json(mergeInsert).path("insertedRows").asLong()).isEqualTo(0L);
    assertThat(json(mergeInsert).path("stats").path("numRows").asLong()).isEqualTo(2L);
    assertExecutionMetadata(P2_ACTIVE_TABLE_ID, 2L, 2L);

    AggregatedHttpResponse update =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/update",
            "{\"predicate\":\"id = 1\",\"values\":{\"text\":\"alpha-updated\"}}");
    assertSuccess(update);
    assertThat(json(update).path("version").asLong()).isEqualTo(3L);
    assertThat(json(update).path("updatedRows").asLong()).isEqualTo(1L);
    assertThat(json(update).path("stats").path("numRows").asLong()).isEqualTo(2L);
    assertExecutionMetadata(P2_ACTIVE_TABLE_ID, 3L, 2L);

    AggregatedHttpResponse delete =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/delete", "{\"predicate\":\"id = 2\"}");
    assertSuccess(delete);
    assertThat(json(delete).path("version").asLong()).isEqualTo(4L);
    assertThat(json(delete).path("deletedRows").asLong()).isEqualTo(1L);
    assertThat(json(delete).path("stats").path("numRows").asLong()).isEqualTo(1L);
    assertExecutionMetadata(P2_ACTIVE_TABLE_ID, 4L, 1L);

    AggregatedHttpResponse count =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows", "{}");
    assertSuccess(count);
    assertThat(json(count).asLong()).isEqualTo(1L);
  }

  @Test
  @DisplayName("P2-WORKER-E2E-006 real LanceDB worker explain_plan returns mock plan")
  void realLanceDbWorkerExplainPlanReturnsMockPlan() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse insert =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            LanceRealLanceDbWorkerProcessFixture.sampleArrowStream());
    assertSuccess(insert);

    AggregatedHttpResponse explain =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/explain_plan",
            "{\"query\":{\"columns\":[\"id\",\"text\"],\"filter\":\"id > 0\"},\"verbose\":true}");
    assertSuccess(explain);
    assertThat(explain.contentUtf8()).contains("LanceScan", "Columns", "Filter");
  }

  @Test
  @DisplayName("P2-WORKER-E2E-007 real LanceDB worker analyze_plan returns mock analysis")
  void realLanceDbWorkerAnalyzePlanReturnsMockAnalysis() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse insert =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            LanceRealLanceDbWorkerProcessFixture.sampleArrowStream());
    assertSuccess(insert);

    AggregatedHttpResponse analyze =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/analyze_plan", "{\"query\":{}}");
    assertSuccess(analyze);
    assertThat(analyze.contentUtf8()).contains("analysis", "estimatedRows", "actualRows");
  }

  private String mergeInsertOptions() {
    return "{\"on\":[\"id\"],\"whenMatchedUpdateAll\":true,\"whenNotMatchedInsertAll\":true}";
  }

  private void assertExecutionMetadata(String identifier, long expectedVersion, long expectedRows) {
    LanceTableRepository repository =
        new Repositories(
                hibernateConfigurator.getSessionFactory(), new ServerProperties(serverProperties))
            .getLanceTableRepository();
    String pathKey = identifierCodec.toPathKey(identifierCodec.decodeIdentifier(identifier, null));
    LanceAssetDAO asset = repository.findAssetByPathKey(pathKey).orElseThrow();
    LanceTableDAO table = repository.findTableByAssetId(asset.getId()).orElseThrow();
    assertThat(asset.getState()).isEqualTo("ACTIVE");
    assertThat(table.getIsOnlyDeclared()).isFalse();
    assertThat(table.getCurrentVersion()).isEqualTo(expectedVersion);
    assertThat(table.getStatsJson()).contains("\"numRows\":" + expectedRows);
  }
}
