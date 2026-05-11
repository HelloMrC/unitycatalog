package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.persist.LanceTableRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.service.lance.backend.LanceTestEchoExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2ConcurrencyRestTest extends BaseLancePhase2RestTest {
  private final LanceIdentifierCodec identifierCodec = new LanceIdentifierCodec();
  private LanceTableRepository tableRepository;

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
    tableRepository =
        new Repositories(
                hibernateConfigurator.getSessionFactory(), new ServerProperties(serverProperties))
            .getLanceTableRepository();
  }

  @Test
  @DisplayName("P2-CONC-001 concurrent query keeps request ids isolated")
  void concurrentQueryKeepsRequestIdsIsolated() throws Exception {
    createActiveTableFixture();
    var executor = Executors.newFixedThreadPool(4);
    try {
      var results =
          executor.invokeAll(
              IntStream.range(0, 4)
                  .mapToObj(
                      i ->
                          (Callable<String>)
                              () -> {
                                AggregatedHttpResponse response =
                                    postJsonWithHeaders(
                                        "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
                                        "{}",
                                        Map.of("x-request-id", "phase2-conc-" + i));
                                assertSuccess(response);
                                return json(response).path("command").path("requestId").asText();
                              })
                  .collect(Collectors.toList()));

      Set<String> requestIds = new HashSet<>();
      for (var result : results) {
        requestIds.add(result.get());
      }
      assertThat(requestIds)
          .containsExactlyInAnyOrder(
              "phase2-conc-0", "phase2-conc-1", "phase2-conc-2", "phase2-conc-3");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("P2-CONC-002 concurrent writes do not move current_version backwards")
  void concurrentWritesDoNotMoveCurrentVersionBackwards() throws Exception {
    createActiveTableFixture();
    LanceAssetDAO asset = asset(P2_ACTIVE_TABLE_ID);
    tableRepository.updateTableExecutionMetadata(
        asset.getId(), 2L, "{\"schema\":\"current\"}", "{\"numRows\":2}", "phase2-conc");

    var responses = runConcurrentArrowInserts(P2_ACTIVE_TABLE_ID, 2);

    for (AggregatedHttpResponse response : responses) {
      assertSuccess(response);
      assertThat(json(response).path("metadataVersionUpdated").asBoolean()).isFalse();
    }
    LanceTableDAO table = tableRepository.findTableByAssetId(asset.getId()).orElseThrow();
    assertThat(table.getCurrentVersion()).isEqualTo(2L);
    assertThat(table.getArrowSchemaJson()).isEqualTo("{\"schema\":\"current\"}");
  }

  @Test
  @DisplayName("P2-CONC-003 concurrent declared materialization has controlled outcome")
  void concurrentDeclaredMaterializationHasControlledOutcome() throws Exception {
    createDeclaredTableFixture();

    var responses = runConcurrentArrowInserts(P2_DECLARED_TABLE_ID, 2);

    assertThat(responses.stream().filter(response -> response.status().isSuccess()).count())
        .isGreaterThanOrEqualTo(1);
    responses.forEach(response -> assertThat(response.status().code()).isIn(200, 201, 204, 409));
    LanceAssetDAO asset = asset(P2_DECLARED_TABLE_ID);
    LanceTableDAO table = tableRepository.findTableByAssetId(asset.getId()).orElseThrow();
    assertThat(asset.getState()).isEqualTo("ACTIVE");
    assertThat(table.getIsOnlyDeclared()).isFalse();
    assertThat(table.getCurrentVersion()).isEqualTo(1L);
  }

  @Test
  @DisplayName("P2-CONC-004 idempotency key is not exposed in plaintext")
  void idempotencyKeyIsNotExposedInPlaintext() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("Idempotency-Key", "phase2-conc-key"));

    assertSuccess(response);
    JsonNode command = json(response).path("command");
    assertThat(command.path("idempotencyKeyHash").asText()).isNotBlank();
    assertThat(response.contentUtf8()).doesNotContain("phase2-conc-key");
  }

  private java.util.List<AggregatedHttpResponse> runConcurrentArrowInserts(
      String tableId, int count) throws Exception {
    var executor = Executors.newFixedThreadPool(count);
    try {
      var results =
          executor.invokeAll(
              IntStream.range(0, count)
                  .mapToObj(
                      ignored ->
                          (Callable<AggregatedHttpResponse>)
                              () ->
                                  postArrow(
                                      "/v1/table/" + tableId + "/insert",
                                      arrowSmallStreamFixture()))
                  .collect(Collectors.toList()));
      java.util.List<AggregatedHttpResponse> responses = new java.util.ArrayList<>();
      for (var result : results) {
        responses.add(result.get());
      }
      return responses;
    } finally {
      executor.shutdownNow();
    }
  }

  private LanceAssetDAO asset(String identifier) {
    return tableRepository.findAssetByPathKey(tablePathKey(identifier)).orElseThrow();
  }

  private String tablePathKey(String identifier) {
    return identifierCodec.toPathKey(identifierCodec.decodeIdentifier(identifier, null));
  }
}
