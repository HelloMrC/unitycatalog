package io.unitycatalog.server.service.lance.backend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.utils.ServerProperties;
import java.util.LinkedHashMap;
import java.util.Map;

public class WorkerHttpLanceExecutionBackend implements LanceExecutionBackend {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final String baseUrl;
  private final boolean readRetryEnabled;
  private final WebClient client;

  public WorkerHttpLanceExecutionBackend(ServerProperties serverProperties) {
    this(
        serverProperties.getLanceExecutionWorkerBaseUrl(),
        serverProperties.isLanceExecutionRetryReadsEnabled());
  }

  WorkerHttpLanceExecutionBackend(String baseUrl) {
    this(baseUrl, true);
  }

  WorkerHttpLanceExecutionBackend(String baseUrl, boolean readRetryEnabled) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT, "lance.execution.worker.base-url must be configured.");
    }
    this.baseUrl = stripTrailingSlash(baseUrl);
    this.readRetryEnabled = readRetryEnabled;
    this.client = WebClient.builder(this.baseUrl).build();
  }

  @Override
  public LanceExecutionResult query(LanceExecutionCommand command) {
    return jsonCommand("query", command, true);
  }

  @Override
  public LanceExecutionResult countRows(LanceExecutionCommand command) {
    return jsonCommand("count_rows", command, true);
  }

  @Override
  public LanceExecutionResult stats(LanceExecutionCommand command) {
    return jsonCommand("stats", command, true);
  }

  @Override
  public LanceExecutionResult insert(LanceExecutionCommand command) {
    return arrowCommand("insert", command);
  }

  @Override
  public LanceExecutionResult mergeInsert(LanceExecutionCommand command) {
    return arrowCommand("merge_insert", command);
  }

  @Override
  public LanceExecutionResult update(LanceExecutionCommand command) {
    return jsonCommand("update", command, false);
  }

  @Override
  public LanceExecutionResult delete(LanceExecutionCommand command) {
    return jsonCommand("delete", command, false);
  }

  @Override
  public LanceExecutionResult explainPlan(LanceExecutionCommand command) {
    return jsonCommand("explain_plan", command, true);
  }

  @Override
  public LanceExecutionResult analyzePlan(LanceExecutionCommand command) {
    return jsonCommand("analyze_plan", command, true);
  }

  @Override
  public LanceExecutionResult create(LanceExecutionCommand command) {
    return arrowCommand("create", command);
  }

  private LanceExecutionResult jsonCommand(
      String operation, LanceExecutionCommand command, boolean retryableRead) {
    return post("/internal/lance/v1/commands/" + operation, command, retryableRead);
  }

  private LanceExecutionResult arrowCommand(String operation, LanceExecutionCommand command) {
    return post("/internal/lance/v1/arrow/" + operation, command, false);
  }

  private LanceExecutionResult post(
      String path, LanceExecutionCommand command, boolean retryableRead) {
    RequestHeaders headers =
        RequestHeaders.builder()
            .method(HttpMethod.POST)
            .path(path)
            .contentType(MediaType.JSON)
            .add(HttpHeaderNames.ACCEPT, MediaType.JSON.toString())
            .add("x-request-id", command.context().requestId())
            .build();
    RequestHeaders requestHeaders = headers(command, headers);
    String commandPayload = writeJson(commandPayload(path, command));
    int retryCount = 0;
    while (true) {
      AggregatedHttpResponse response =
          client.execute(requestHeaders, HttpData.ofUtf8(commandPayload)).aggregate().join();
      if (response.status().isSuccess()) {
        Map<String, Object> payload = readJson(response.contentUtf8());
        payload.putIfAbsent("backendType", "worker-http");
        payload.put("retryCount", retryCount);
        payload.put("retryAttempted", retryCount > 0);
        if (retryCount > 0) {
          payload.put("retryReason", "worker_503");
        }
        return new LanceExecutionResult(payload);
      }
      if (shouldRetry(response, retryableRead, retryCount)) {
        retryCount++;
        continue;
      }
      throw workerError(response);
    }
  }

  private RequestHeaders headers(LanceExecutionCommand command, RequestHeaders baseHeaders) {
    RequestHeadersBuilder builder = baseHeaders.toBuilder();
    if (command.context().deadlineMs() != null) {
      builder.set("x-lance-deadline-ms", String.valueOf(command.context().deadlineMs()));
    }
    if (command.context().idempotencyKeyHash() != null) {
      builder.set("x-lance-idempotency-key-sha256", command.context().idempotencyKeyHash());
    }
    return builder.build();
  }

  private Map<String, Object> commandPayload(String path, LanceExecutionCommand command) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("operation", command.operation());
    payload.put("workerPath", path);
    payload.put("workerBaseUrl", baseUrl);
    payload.put("context", command.context().lanceContext());
    payload.put("table", command.table());
    payload.put("storage", command.storage());
    payload.put("requestId", command.context().requestId());
    payload.put("principal", command.context().principal());
    payload.put("authType", command.context().authType());
    payload.put("deadlineMs", command.context().deadlineMs());
    payload.put("idempotencyKeyHash", command.context().idempotencyKeyHash());
    payload.put("tableId", command.table().id());
    payload.put("pathKey", command.table().pathKey());
    payload.put("tableUri", command.table().tableUri());
    payload.put("legacyBridge", command.table().legacyBridge());
    payload.putAll(command.attributes());
    return payload;
  }

  private LanceBackendException workerError(AggregatedHttpResponse response) {
    Map<String, Object> error = readJson(response.contentUtf8());
    return new LanceBackendException(
        response.status(),
        stringValue(error.getOrDefault("type", "worker_error")),
        stringValue(error.get("backend_request_id")),
        stringValue(error.getOrDefault("message", "Lance worker request failed.")));
  }

  private boolean shouldRetry(
      AggregatedHttpResponse response, boolean retryableRead, int retryCount) {
    return retryableRead
        && readRetryEnabled
        && retryCount == 0
        && response.status().equals(HttpStatus.SERVICE_UNAVAILABLE);
  }

  private Map<String, Object> readJson(String content) {
    if (content == null || content.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return new LinkedHashMap<>(OBJECT_MAPPER.readValue(content, MAP_TYPE));
    } catch (JsonProcessingException e) {
      throw new BaseException(ErrorCode.INTERNAL, "Invalid Lance worker JSON response.", e);
    }
  }

  private String writeJson(Map<String, Object> payload) {
    try {
      return OBJECT_MAPPER.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new BaseException(ErrorCode.INTERNAL, "Invalid Lance worker command payload.", e);
    }
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
