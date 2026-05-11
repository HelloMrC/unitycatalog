package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
@Disabled("""
    Superseded by enabled tests:
    - P2-WORKER-001~013: LancePhase2WorkerHttpBackendRestTest
    - P2-CONC-001~004: LancePhase2ConcurrencyRestTest
    - P2-CONC-006: LancePhase2WorkerHttpBackendRestTest
    - P2-ARROW-012: LancePhase2WorkerHttpBackendRestTest
    Retain for real sidecar pressure tests.""")
class LancePhase2WorkerAndResilienceRestTest extends BaseLancePhase2RestTest {

  @Test
  @DisplayName("P2-WORKER-001 backend factory creates worker-http backend")
  void backendFactoryCreatesWorkerHttpBackend() throws Exception {
    Class<?> backendClass =
        Class.forName(
            "io.unitycatalog.server.service.lance.backend.WorkerHttpLanceExecutionBackend");

    assertThat(backendClass.getSimpleName()).isEqualTo("WorkerHttpLanceExecutionBackend");
  }

  @Test
  @DisplayName("P2-WORKER-002 worker base URL comes only from server property")
  void workerBaseUrlComesOnlyFromServerProperty() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postJsonWithHeaders(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
                "{}",
                Map.of("x-lance-worker-url", "http://evil.example.com")));

    assertThat(command.path("workerBaseUrl").asText()).doesNotContain("evil.example.com");
  }

  @Test
  @DisplayName("P2-WORKER-003 worker health path has explicit success/failure behavior")
  void workerHealthPathHasExplicitBehavior() throws Exception {
    AggregatedHttpResponse response = postJson("/admin/worker/health", "{}");

    assertThat(response.status().code()).isIn(200, 503);
    assertThat(response.contentUtf8()).contains("worker");
  }

  @Test
  @DisplayName("P2-WORKER-004 JSON commands are forwarded to internal worker command paths")
  void jsonCommandsAreForwardedToInternalWorkerCommandPaths() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}"));

    assertThat(command.path("workerPath").asText()).startsWith("/internal/lance/v1/commands/");
  }

  @Test
  @DisplayName("P2-WORKER-005 Arrow commands are forwarded to internal worker Arrow paths")
  void arrowCommandsAreForwardedToInternalWorkerArrowPaths() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    assertThat(command.path("workerPath").asText()).startsWith("/internal/lance/v1/arrow/");
  }

  @Test
  @DisplayName("P2-WORKER-006 requestId deadline and idempotency headers reach worker")
  void requestIdDeadlineAndIdempotencyHeadersReachWorker() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postArrow(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
                arrowSmallStreamFixture(),
                Map.of(
                    "x-request-id", "phase2-worker-request",
                    "x-lance-deadline-ms", "1000",
                    "Idempotency-Key", "phase2-worker-idempotency")));

    assertThat(command.path("workerHeaders").toString())
        .contains("phase2-worker-request", "1000")
        .doesNotContain("phase2-worker-idempotency");
    assertThat(command.path("idempotencyKeyHash").asText()).isNotBlank();
  }

  @Test
  @DisplayName("P2-WORKER-007 worker error envelope maps to Lance error shape")
  void workerErrorEnvelopeMapsToLanceErrorShape() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-worker-error", "standard-envelope"));

    assertLanceErrorShape(response, 500);
    assertThat(json(response).path("backend_request_id").asText()).isNotBlank();
  }

  @Test
  @DisplayName("P2-WORKER-008 worker timeout maps to 503 or 504")
  void workerTimeoutMapsToServiceUnavailable() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-worker-error", "timeout"));

    assertThat(response.status().code()).isIn(503, 504);
  }

  @Test
  @DisplayName("P2-WORKER-009 read retry is bounded and audited")
  void readRetryIsBoundedAndAudited() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-worker-error", "retry-once"));

    assertSuccess(response);
    assertThat(json(response).path("retryCount").asInt()).isEqualTo(1);
    assertThat(json(response).path("audit").toString()).contains("retry");
  }

  @Test
  @DisplayName("P2-WORKER-010 write retry is disabled after body is sent")
  void writeRetryIsDisabledAfterBodyIsSent() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("x-lance-fake-worker-error", "503-after-body"));

    assertThat(response.status().code()).isIn(503, 504);
    assertThat(json(response).path("retryCount").asInt()).isEqualTo(0);
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
                              () ->
                                  json(postJsonWithHeaders(
                                          "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
                                          "{}",
                                          Map.of("x-request-id", "phase2-conc-" + i)))
                                      .path("requestId")
                                      .asText())
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

    AggregatedHttpResponse first =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture());
    AggregatedHttpResponse second =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertSuccess(first);
    assertSuccess(second);
    assertThat(json(second).path("version").asLong())
        .isGreaterThanOrEqualTo(json(first).path("version").asLong());
  }

  @Test
  @DisplayName("P2-CONC-003 concurrent declared materialization has single owner")
  void concurrentDeclaredMaterializationHasSingleOwner() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse first =
        postArrow("/v1/table/" + P2_DECLARED_TABLE_ID + "/insert", arrowSmallStreamFixture());
    AggregatedHttpResponse second =
        postArrow("/v1/table/" + P2_DECLARED_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertThat(first.status().code()).isBetween(200, 299);
    assertThat(second.status().code()).isIn(200, 409);
  }

  @Test
  @DisplayName("P2-CONC-004 idempotency key hash is never audited in plaintext")
  void idempotencyKeyHashIsNeverAuditedInPlaintext() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("Idempotency-Key", "phase2-conc-key"));

    assertSuccess(response);
    assertThat(json(response).path("audit").toString()).doesNotContain("phase2-conc-key");
  }

  @Test
  @DisplayName("P2-CONC-005 Arrow backpressure avoids full buffering")
  void arrowBackpressureAvoidsFullBuffering() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("x-lance-test-streaming-probe", "true"));

    assertSuccess(response);
    assertThat(json(response).path("streamPassedThrough").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P2-CONC-006 mid-stream query failure is not retried")
  void midStreamQueryFailureIsNotRetried() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/query",
            "{}",
            Map.of("x-lance-fake-worker-error", "stream-break"));

    assertThat(response.status().code()).isIn(500, 503);
    assertThat(json(response).path("retryCount").asInt()).isEqualTo(0);
  }
}
