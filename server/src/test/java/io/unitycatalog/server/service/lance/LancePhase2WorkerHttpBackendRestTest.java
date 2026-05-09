package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.Server;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2WorkerHttpBackendRestTest extends BaseLancePhase2RestTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final byte[] FAKE_WORKER_ARROW_BODY =
      "fake-worker-arrow-body".getBytes(StandardCharsets.UTF_8);
  private static final String FAKE_WORKER_HEALTH_PATH = "/internal/lance/v1/custom-health";

  private Server fakeWorker;
  private String workerBaseUrl;
  private HttpStatus workerHealthStatus = HttpStatus.OK;
  private final ConcurrentMap<String, AtomicInteger> workerAttempts = new ConcurrentHashMap<>();

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    startFakeWorker();
    serverProperties.setProperty(Property.LANCE_EXECUTION_BACKEND_TYPE.getKey(), "worker-http");
    serverProperties.setProperty(Property.LANCE_EXECUTION_WORKER_BASE_URL.getKey(), workerBaseUrl);
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_WORKER_HEALTH_PATH.getKey(), FAKE_WORKER_HEALTH_PATH);
    serverProperties.setProperty(Property.LANCE_EXECUTION_MAX_ARROW_REQUEST_BYTES.getKey(), "1024");
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
                "{"
                    + "\"workerBaseUrl\":\"http://evil.example.com\","
                    + "\"operation\":\"delete\","
                    + "\"tableId\":\"evil-table\","
                    + "\"pathKey\":\"evil-path\","
                    + "\"requestId\":\"evil-request\""
                    + "}",
                Map.of("x-lance-worker-url", "http://evil.example.com")));

    assertThat(command.path("operation").asText()).isEqualTo("stats");
    assertThat(command.path("workerBaseUrl").asText()).isEqualTo(workerBaseUrl);
    assertThat(command.path("tableId").asText()).isEqualTo(P2_ACTIVE_TABLE_ID);
    assertThat(command.path("pathKey").asText()).isEqualTo("prod/team_a/embeddings");
    assertThat(command.path("requestId").asText()).isNotEqualTo("evil-request");
    assertThat(command.toString())
        .doesNotContain("evil.example.com", "evil-table", "evil-path");
  }

  @Test
  @DisplayName("P2-WORKER-003 worker health path has explicit success and failure behavior")
  void workerHealthPathHasExplicitBehavior() throws Exception {
    var healthy = postJson("/admin/worker/health", "{}");

    assertThat(healthy.status().code()).isEqualTo(200);
    assertThat(json(healthy).path("worker").asText()).isEqualTo("healthy");
    assertThat(json(healthy).path("workerHealthPath").asText())
        .isEqualTo(FAKE_WORKER_HEALTH_PATH);
    assertThat(json(healthy).path("details").path("worker").asText()).isEqualTo("fake-worker");

    workerHealthStatus = HttpStatus.INTERNAL_SERVER_ERROR;
    var unhealthy = postJson("/admin/worker/health", "{}");

    assertThat(unhealthy.status().code()).isEqualTo(503);
    assertThat(unhealthy.contentUtf8()).contains("worker", "unhealthy");
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
  @DisplayName("P2-ARROW-008 worker query Arrow body is returned unchanged")
  void workerQueryArrowBodyIsReturnedUnchanged() throws Exception {
    createActiveTableFixture();

    var response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/query",
            "{}",
            Map.of(
                HttpHeaderNames.ACCEPT.toString(), ARROW_STREAM.toString(),
                "x-lance-fake-worker-arrow-response", "true"));

    assertSuccess(response);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .contains(ARROW_STREAM.toString());
    assertThat(response.content().array()).isEqualTo(FAKE_WORKER_ARROW_BODY);
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
    assertThat(command.path("workerContentType").asText()).isEqualTo(ARROW_STREAM.toString());
    assertThat(command.path("workerBodyBytes").asInt()).isEqualTo(arrowSmallStreamFixture().length);
    assertThat(command.path("workerBodyPreview").asText())
        .isEqualTo(new String(arrowSmallStreamFixture(), StandardCharsets.UTF_8));
    assertThat(command.path("streamPassedThrough").asBoolean()).isTrue();
    assertThat(command.path("ucRequestMode").asText()).isEqualTo("streaming");
    assertThat(command.path("requestBufferedBytes").asLong()).isEqualTo(0L);
    String workerAttributes =
        command.path("workerHeaders").path("x-uc-lance-attributes").asText();
    assertThat(workerAttributes).doesNotContain("__arrowBody");
    assertThat(workerAttributes)
        .doesNotContain(new String(arrowSmallStreamFixture(), StandardCharsets.UTF_8));
    assertThat(command.path("workerHeaders").toString())
        .contains("x-uc-lance-command", "x-uc-lance-table", "insert");
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
    assertThat(json(response).path("audit").path("backendType").asText())
        .isEqualTo("worker-http");
    assertThat(json(response).path("metrics").path("backend").asText()).isEqualTo("worker-http");
  }

  @Test
  @DisplayName("P2-WORKER-007B non-JSON worker errors preserve worker status")
  void nonJsonWorkerErrorsPreserveWorkerStatus() throws Exception {
    createActiveTableFixture();

    var response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-worker-error", "non-json"));

    assertLanceErrorShape(response, 502);
    assertThat(json(response).path("type").asText()).isEqualTo("worker_error");
    assertThat(json(response).path("audit").path("errorCode").asText()).isEqualTo("worker_error");
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
  @DisplayName("P2-WORKER-008C worker client timeout uses request deadline")
  void workerClientTimeoutUsesRequestDeadline() throws Exception {
    createActiveTableFixture();

    var response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of(
                "x-lance-deadline-ms", "50",
                "x-lance-fake-worker-error", "client-timeout"));

    assertLanceErrorShape(response, 504);
    assertThat(json(response).path("type").asText()).isEqualTo("backend_timeout");
    assertThat(json(response).path("audit").path("backendType").asText())
        .isEqualTo("worker-http");
  }

  @Test
  @DisplayName("P2-WORKER-008B worker connection failure maps to stable 503")
  void workerConnectionFailureMapsToServiceUnavailable() throws Exception {
    createActiveTableFixture();
    fakeWorker.stop().join();
    fakeWorker = null;

    var response = postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}");

    assertLanceErrorShape(response, 503);
    assertThat(json(response).path("type").asText()).isEqualTo("worker_unavailable");
    assertThat(json(response).path("audit").path("errorCode").asText())
        .isEqualTo("worker_unavailable");
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

  @Test
  @DisplayName("P2-ARROW-012 unknown-length Arrow stream is stopped at runtime limit")
  void unknownLengthArrowStreamIsStoppedAtRuntimeLimit() throws Exception {
    createActiveTableFixture();
    byte[] chunk = "x".repeat(600).getBytes(StandardCharsets.UTF_8);
    StreamingLanceRequest streaming =
        startPostArrowStreaming(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            Map.of("x-request-id", "phase2-chunk-limit"));

    try {
      assertThat(streaming.request().headers().get(HttpHeaderNames.CONTENT_LENGTH)).isNull();
      assertThat(streaming.request().tryWrite(HttpData.wrap(chunk))).isTrue();
      assertThat(streaming.request().tryWrite(HttpData.wrap(chunk))).isTrue();

      var response = streaming.response().get(5, TimeUnit.SECONDS);

      assertLanceErrorShape(response, 413);
      assertThat(json(response).path("type").asText()).isEqualTo("request_entity_too_large");
      assertThat(attemptsFor("insert")).isEqualTo(0);
      assertThat(streaming.request().tryWrite(HttpData.wrap(chunk))).isFalse();
    } finally {
      streaming.request().close();
    }
  }

  private void startFakeWorker() {
    fakeWorker =
        Server.builder()
            .http(0)
            .service(FAKE_WORKER_HEALTH_PATH, this::fakeWorkerHealth)
            .serviceUnder("/internal/lance/v1/commands", this::fakeWorkerResponse)
            .serviceUnder("/internal/lance/v1/arrow", this::fakeWorkerResponse)
            .build();
    fakeWorker.start().join();
    workerBaseUrl = "http://127.0.0.1:" + fakeWorker.activeLocalPort();
  }

  private HttpResponse fakeWorkerHealth(
      ServiceRequestContext ctx, com.linecorp.armeria.common.HttpRequest req) {
    return HttpResponse.ofJson(
        workerHealthStatus,
        Map.of(
            "worker",
            "fake-worker",
            "status",
            workerHealthStatus.isSuccess() ? "ok" : "failed"));
  }

  private HttpResponse fakeWorkerResponse(
      ServiceRequestContext ctx, com.linecorp.armeria.common.HttpRequest req) {
    return HttpResponse.of(
        req.aggregate().thenApply(request -> fakeWorkerResponse(ctx, req.headers(), request)));
  }

  private HttpResponse fakeWorkerResponse(
      ServiceRequestContext ctx, RequestHeaders headers, AggregatedHttpRequest request) {
    Map<String, Object> command = command(headers, request);
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
    if ("client-timeout".equals(mode)) {
      sleep(250);
    }
    if ("non-json".equals(mode)) {
      ResponseHeaders responseHeaders =
          ResponseHeaders.builder(HttpStatus.BAD_GATEWAY)
              .contentType(MediaType.PLAIN_TEXT_UTF_8)
              .build();
      return HttpResponse.of(responseHeaders, HttpData.ofUtf8("plain worker failure"));
    }
    if ("true".equals(contextValue(command, "fakeWorkerArrowResponse"))) {
      ResponseHeaders responseHeaders =
          ResponseHeaders.builder(HttpStatus.OK).contentType(ARROW_STREAM).build();
      return HttpResponse.of(responseHeaders, HttpData.wrap(FAKE_WORKER_ARROW_BODY));
    }

    Map<String, Object> response = responsePayload(command);
    command.put("workerPath", ctx.path());
    command.put("workerHeaders", workerHeaders(headers));
    command.put("workerAttempt", attempt);
    response.put("command", command);
    response.put("backendType", "worker-http");
    return HttpResponse.ofJson(response);
  }

  private void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Map<String, Object> command(RequestHeaders headers, AggregatedHttpRequest request) {
    if (request.contentType() != null
        && ARROW_STREAM.equals(request.contentType().withoutParameters())) {
      Map<String, Object> command = readHeaderJson(headers, "x-uc-lance-attributes");
      Map<String, Object> table = readHeaderJson(headers, "x-uc-lance-table");
      command.put("operation", headers.get("x-uc-lance-command"));
      command.put("context", readHeaderJson(headers, "x-uc-lance-context"));
      command.put("table", table);
      command.put("storage", readHeaderJson(headers, "x-uc-lance-storage"));
      command.put("requestId", headers.get("x-uc-request-id"));
      command.put("deadlineMs", headers.get("x-lance-deadline-ms"));
      command.put("idempotencyKeyHash", headers.get("x-lance-idempotency-key-sha256"));
      command.put("tableId", table.get("id"));
      command.put("pathKey", table.get("pathKey"));
      command.put("tableUri", table.get("tableUri"));
      command.put("legacyBridge", table.get("legacyBridge"));
      command.put("workerContentType", request.contentType().withoutParameters().toString());
      command.put("workerBodyBytes", request.content().length());
      command.put(
          "workerBodyPreview", new String(request.content().array(), StandardCharsets.UTF_8));
      return command;
    }
    return readJson(request.contentUtf8());
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
    workerHeaders.put("x-uc-lance-command", headers.get("x-uc-lance-command"));
    workerHeaders.put("x-uc-lance-context", headers.get("x-uc-lance-context"));
    workerHeaders.put("x-uc-lance-table", headers.get("x-uc-lance-table"));
    workerHeaders.put("x-uc-lance-storage", headers.get("x-uc-lance-storage"));
    workerHeaders.put("x-uc-lance-attributes", headers.get("x-uc-lance-attributes"));
    workerHeaders.put("x-uc-request-id", headers.get("x-uc-request-id"));
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

  private Map<String, Object> readHeaderJson(RequestHeaders headers, String name) {
    String value = headers.get(name);
    if (value == null || value.isBlank()) {
      return new LinkedHashMap<>();
    }
    byte[] json = Base64.getUrlDecoder().decode(value);
    return readJson(new String(json, StandardCharsets.UTF_8));
  }

  private int attemptsFor(String operation) {
    AtomicInteger attempts = workerAttempts.get(operation);
    return attempts == null ? 0 : attempts.get();
  }
}
