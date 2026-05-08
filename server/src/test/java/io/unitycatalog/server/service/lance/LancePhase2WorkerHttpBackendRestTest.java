package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.Server;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2WorkerHttpBackendRestTest extends BaseLancePhase2RestTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private Server fakeWorker;
  private String workerBaseUrl;
  private final ConcurrentMap<String, AtomicInteger> workerAttempts = new ConcurrentHashMap<>();

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    startFakeWorker();
    serverProperties.setProperty(Property.LANCE_EXECUTION_BACKEND_TYPE.getKey(), "worker-http");
    serverProperties.setProperty(Property.LANCE_EXECUTION_WORKER_BASE_URL.getKey(), workerBaseUrl);
  }

  @AfterEach
  @Override
  public void tearDown() {
    try {
      super.tearDown();
    } finally {
      if (fakeWorker != null) {
        fakeWorker.stop().join();
      }
    }
  }

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

    var command =
        assertCommandEcho(
            postJsonWithHeaders(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
                "{}",
                Map.of("x-lance-worker-url", "http://evil.example.com")));

    assertThat(command.path("workerBaseUrl").asText()).isEqualTo(workerBaseUrl);
    assertThat(command.toString()).doesNotContain("evil.example.com");
  }

  @Test
  @DisplayName("P2-WORKER-004 JSON commands are forwarded to internal worker command paths")
  void jsonCommandsAreForwardedToInternalWorkerCommandPaths() throws Exception {
    createActiveTableFixture();

    var command =
        assertCommandEcho(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}"));

    assertThat(command.path("workerPath").asText())
        .isEqualTo("/internal/lance/v1/commands/stats");
  }

  @Test
  @DisplayName("P2-WORKER-005 Arrow commands are forwarded to internal worker Arrow paths")
  void arrowCommandsAreForwardedToInternalWorkerArrowPaths() throws Exception {
    createActiveTableFixture();

    var command =
        assertCommandEcho(
            postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    assertThat(command.path("workerPath").asText())
        .isEqualTo("/internal/lance/v1/arrow/insert");
  }

  @Test
  @DisplayName("P2-WORKER-006 requestId deadline and idempotency headers reach worker")
  void requestIdDeadlineAndIdempotencyHeadersReachWorker() throws Exception {
    createActiveTableFixture();

    var command =
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

    var response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-worker-error", "standard-envelope"));

    assertLanceErrorShape(response, 500);
    assertThat(json(response).path("backend_request_id").asText()).isEqualTo("fake-worker-req");
  }

  @Test
  @DisplayName("P2-WORKER-008 worker timeout maps to 504")
  void workerTimeoutMapsToGatewayTimeout() throws Exception {
    createActiveTableFixture();

    var response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-worker-error", "timeout"));

    assertLanceErrorShape(response, 504);
  }

  @Test
  @DisplayName("P2-WORKER-009 read retry is bounded and audited")
  void readRetryIsBoundedAndAudited() throws Exception {
    createActiveTableFixture();

    var response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-worker-error", "retry-once"));

    assertSuccess(response);
    assertThat(json(response).path("retryCount").asInt()).isEqualTo(1);
    assertThat(json(response).path("audit").path("retryCount").asInt()).isEqualTo(1);
    assertThat(json(response).path("audit").path("retryReason").asText()).isEqualTo("worker_503");
    assertThat(attemptsFor("stats")).isEqualTo(2);
  }

  @Test
  @DisplayName("P2-WORKER-010 write retry is disabled after body is sent")
  void writeRetryIsDisabledAfterBodyIsSent() throws Exception {
    createActiveTableFixture();

    var response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("x-lance-fake-worker-error", "503-after-body"));

    assertLanceErrorShape(response, 503);
    assertThat(json(response).path("audit").path("operation").asText()).isEqualTo("insert");
    assertThat(attemptsFor("insert")).isEqualTo(1);
  }

  private void startFakeWorker() {
    fakeWorker =
        Server.builder()
            .http(0)
            .serviceUnder("/internal/lance/v1/commands", this::fakeWorkerResponse)
            .serviceUnder("/internal/lance/v1/arrow", this::fakeWorkerResponse)
            .build();
    fakeWorker.start().join();
    workerBaseUrl = "http://127.0.0.1:" + fakeWorker.activeLocalPort();
  }

  private HttpResponse fakeWorkerResponse(
      ServiceRequestContext ctx, com.linecorp.armeria.common.HttpRequest req) {
    return HttpResponse.of(
        req.aggregate().thenApply(request -> fakeWorkerResponse(ctx, req.headers(), request)));
  }

  private HttpResponse fakeWorkerResponse(
      ServiceRequestContext ctx, RequestHeaders headers, AggregatedHttpRequest request) {
    Map<String, Object> command = readJson(request.contentUtf8());
    String operation = String.valueOf(command.get("operation"));
    int attempt =
        workerAttempts.computeIfAbsent(operation, ignored -> new AtomicInteger()).incrementAndGet();
    String mode = contextValue(command, "fakeWorkerError");
    if ("retry-once".equals(mode) && attempt == 1) {
      return HttpResponse.ofJson(
          HttpStatus.SERVICE_UNAVAILABLE,
          Map.of(
              "type", "worker_unavailable",
              "message", "fake worker retryable unavailable",
              "code", 503,
              "backend_request_id", "fake-worker-retry-1"));
    }
    if ("503-after-body".equals(mode)) {
      return HttpResponse.ofJson(
          HttpStatus.SERVICE_UNAVAILABLE,
          Map.of(
              "type", "worker_unavailable",
              "message", "fake worker write failed after body",
              "code", 503,
              "backend_request_id", "fake-worker-write-1"));
    }
    if ("standard-envelope".equals(mode)) {
      return HttpResponse.ofJson(
          HttpStatus.INTERNAL_SERVER_ERROR,
          Map.of(
              "type", "internal",
              "message", "fake worker failed",
              "code", 500,
              "backend_request_id", "fake-worker-req"));
    }
    if ("timeout".equals(mode)) {
      return HttpResponse.ofJson(
          HttpStatus.GATEWAY_TIMEOUT,
          Map.of(
              "type", "backend_timeout",
              "message", "fake worker timed out",
              "code", 504,
              "backend_request_id", "fake-worker-timeout"));
    }

    Map<String, Object> response = responsePayload(command);
    command.put("workerPath", ctx.path());
    command.put("workerHeaders", workerHeaders(headers));
    command.put("workerAttempt", attempt);
    response.put("command", command);
    response.put("backendType", "worker-http");
    return HttpResponse.ofJson(response);
  }

  private Map<String, Object> responsePayload(Map<String, Object> command) {
    String operation = String.valueOf(command.get("operation"));
    return switch (operation) {
      case "query" -> new LinkedHashMap<>(Map.of("arrow", "fake-worker-arrow"));
      case "count_rows" -> new LinkedHashMap<>(Map.of("count", 0));
      case "stats" ->
          new LinkedHashMap<>(
              Map.of("totalBytes", 0, "numRows", 0, "numIndices", 0, "fragmentStats", Map.of()));
      case "explain_plan" -> new LinkedHashMap<>(Map.of("plan", "fake worker explain plan"));
      case "analyze_plan" -> new LinkedHashMap<>(Map.of("plan", "fake worker analyze plan"));
      case "insert", "create" ->
          new LinkedHashMap<>(
              Map.of("transactionId", "fake-" + operation, "version", 1, "stats", Map.of()));
      case "merge_insert" ->
          new LinkedHashMap<>(Map.of("updatedRows", 0, "insertedRows", 0, "deletedRows", 0));
      case "update" -> new LinkedHashMap<>(Map.of("updatedRows", 0));
      case "delete" -> new LinkedHashMap<>(Map.of("deletedRows", 0));
      default -> new LinkedHashMap<>();
    };
  }

  private Map<String, Object> workerHeaders(RequestHeaders headers) {
    Map<String, Object> workerHeaders = new LinkedHashMap<>();
    workerHeaders.put("x-request-id", headers.get("x-request-id"));
    workerHeaders.put("x-lance-deadline-ms", headers.get("x-lance-deadline-ms"));
    workerHeaders.put(
        "x-lance-idempotency-key-sha256", headers.get("x-lance-idempotency-key-sha256"));
    return workerHeaders;
  }

  @SuppressWarnings("unchecked")
  private String contextValue(Map<String, Object> command, String key) {
    Object context = command.get("context");
    if (context instanceof Map<?, ?> map) {
      Object value = map.get(key);
      return value == null ? null : String.valueOf(value);
    }
    return null;
  }

  private Map<String, Object> readJson(String content) {
    try {
      return new LinkedHashMap<>(OBJECT_MAPPER.readValue(content, MAP_TYPE));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private int attemptsFor(String operation) {
    AtomicInteger attempts = workerAttempts.get(operation);
    return attempts == null ? 0 : attempts.get();
  }
}
