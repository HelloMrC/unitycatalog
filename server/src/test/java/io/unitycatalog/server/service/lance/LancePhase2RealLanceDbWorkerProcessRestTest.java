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
