package io.unitycatalog.server.service.lance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.service.lance.backend.LanceExecutionBackend;
import io.unitycatalog.server.service.lance.backend.LanceExecutionCommand;
import io.unitycatalog.server.service.lance.backend.LanceExecutionResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ExceptionHandler(LanceExceptionHandler.class)
public class LanceRestTableDataService {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final String REQUEST_ID_HEADER = "x-request-id";
  private static final String IDEMPOTENCY_KEY_HEADER = "idempotency-key";

  private final LanceExecutionBackend backend;

  public LanceRestTableDataService(LanceExecutionBackend backend) {
    this.backend = backend;
  }

  @Post("/v1/table/{id}/query")
  public HttpResponse queryTable(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    LanceExecutionCommand command =
        command("query", id, delimiter, queryAttributes(jsonBody(request)), request);
    return json(backend.query(command), command);
  }

  @Post("/v1/table/{id}/count_rows")
  public HttpResponse countRows(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    LanceExecutionCommand command =
        command("count_rows", id, delimiter, safeBody(jsonBody(request)), request);
    return json(backend.countRows(command), command);
  }

  @Post("/v1/table/{id}/stats")
  public HttpResponse stats(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    LanceExecutionCommand command =
        command("stats", id, delimiter, safeBody(jsonBody(request)), request);
    return json(backend.stats(command), command);
  }

  @Post("/v1/table/{id}/insert")
  public HttpResponse insert(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    LanceExecutionCommand command =
        command("insert", id, delimiter, arrowAttributes(request, Optional.empty()), request);
    return json(backend.insert(command), command);
  }

  @Post("/v1/table/{id}/merge_insert")
  public HttpResponse mergeInsert(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    LanceExecutionCommand command =
        command(
            "merge_insert",
            id,
            delimiter,
            arrowAttributes(request, Optional.of("x-lance-merge-options")),
            request);
    return json(backend.mergeInsert(command), command);
  }

  @Post("/v1/table/{id}/update")
  public HttpResponse update(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    LanceExecutionCommand command =
        command("update", id, delimiter, safeBody(jsonBody(request)), request);
    return json(backend.update(command), command);
  }

  @Post("/v1/table/{id}/delete")
  public HttpResponse delete(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    LanceExecutionCommand command =
        command("delete", id, delimiter, safeBody(jsonBody(request)), request);
    return json(backend.delete(command), command);
  }

  @Post("/v1/table/{id}/explain_plan")
  public HttpResponse explainPlan(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    LanceExecutionCommand command =
        command("explain_plan", id, delimiter, planAttributes(jsonBody(request)), request);
    return json(backend.explainPlan(command), command);
  }

  @Post("/v1/table/{id}/analyze_plan")
  public HttpResponse analyzePlan(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    LanceExecutionCommand command =
        command("analyze_plan", id, delimiter, planAttributes(jsonBody(request)), request);
    return json(backend.analyzePlan(command), command);
  }

  @Post("/v1/table/{id}/create")
  public HttpResponse create(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    LanceExecutionCommand command =
        command(
            "create",
            id,
            delimiter,
            arrowAttributes(request, Optional.of("x-lance-create-options")),
            request);
    return json(backend.create(command), command);
  }

  private LanceExecutionCommand command(
      String operation,
      String id,
      Optional<String> delimiter,
      Map<String, Object> operationAttributes,
      AggregatedHttpRequest request) {
    Map<String, Object> attributes = new LinkedHashMap<>(operationAttributes);
    attributes.put("requestId", requestId(request));
    String principal = LanceRequestContext.currentPrincipal();
    if (principal != null && !principal.isBlank()) {
      attributes.put("principal", principal);
    }
    attributes.put("authType", authType(request));
    Map<String, String> contextHeaders = LanceRequestContext.currentContextHeaders();
    if (!contextHeaders.isEmpty()) {
      attributes.put("context", context(contextHeaders));
    }
    String idempotencyKey = request.headers().get(IDEMPOTENCY_KEY_HEADER);
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      attributes.put("idempotencyKeyHash", sha256(idempotencyKey));
    }
    return new LanceExecutionCommand(operation, id, delimiter.orElse(null), attributes);
  }

  private HttpResponse json(LanceExecutionResult result, LanceExecutionCommand command) {
    Object requestId = command.attributes().get("requestId");
    ResponseHeaders headers =
        ResponseHeaders.builder(HttpStatus.OK)
            .contentType(com.linecorp.armeria.common.MediaType.JSON_UTF_8)
            .add(REQUEST_ID_HEADER, String.valueOf(requestId))
            .build();
    return HttpResponse.ofJson(headers, result.payload());
  }

  private Map<String, Object> jsonBody(AggregatedHttpRequest request) {
    String body = request.contentUtf8();
    if (body == null || body.isBlank()) {
      return Map.of();
    }
    try {
      return OBJECT_MAPPER.readValue(body, MAP_TYPE);
    } catch (JsonProcessingException e) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Invalid JSON request body.", e);
    }
  }

  private Map<String, Object> queryAttributes(Map<String, Object> body) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("querySpec", safeBody(body));
    attributes.put("responseFormat", "arrow");
    return attributes;
  }

  private Map<String, Object> planAttributes(Map<String, Object> body) {
    Map<String, Object> attributes = new LinkedHashMap<>(safeBody(body));
    if (attributes.containsKey("query")) {
      attributes.put("querySpec", attributes.remove("query"));
    }
    return attributes;
  }

  private Map<String, Object> arrowAttributes(
      AggregatedHttpRequest request, Optional<String> optionsHeader) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("inputData", "arrow-stream");
    attributes.put("inputBytes", request.content().length());
    optionsHeader
        .map(header -> request.headers().get(header))
        .filter(value -> !value.isBlank())
        .ifPresent(value -> attributes.putAll(safeBody(parseJsonHeader(value))));
    return attributes;
  }

  private Map<String, Object> parseJsonHeader(String value) {
    try {
      return OBJECT_MAPPER.readValue(value, MAP_TYPE);
    } catch (JsonProcessingException e) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Invalid Lance options header.", e);
    }
  }

  private Map<String, Object> safeBody(Map<String, Object> body) {
    Map<String, Object> safe = new LinkedHashMap<>(body);
    safe.remove("id");
    safe.remove("identity");
    safe.remove("principal");
    safe.remove("authType");
    safe.remove("requestId");
    safe.remove("context");
    return safe;
  }

  private String requestId(AggregatedHttpRequest request) {
    String requestId = request.headers().get(REQUEST_ID_HEADER);
    return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
  }

  private String authType(AggregatedHttpRequest request) {
    String authorization = request.headers().get("authorization");
    if (authorization != null && authorization.startsWith("Bearer ")) {
      return "bearer";
    }
    String apiKey = request.headers().get("x-api-key");
    return apiKey == null || apiKey.isBlank() ? "anonymous" : "api_key";
  }

  private Map<String, String> context(Map<String, String> contextHeaders) {
    Map<String, String> context = new LinkedHashMap<>();
    contextHeaders.forEach((key, value) -> context.put(contextKey(key), value));
    return context;
  }

  private String contextKey(String header) {
    String normalized =
        header.startsWith("x-lance-") ? header.substring("x-lance-".length()) : header;
    StringBuilder builder = new StringBuilder();
    boolean upperNext = false;
    for (char c : normalized.toCharArray()) {
      if (c == '-') {
        upperNext = true;
      } else if (upperNext) {
        builder.append(Character.toUpperCase(c));
        upperNext = false;
      } else {
        builder.append(c);
      }
    }
    return builder.toString();
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder();
      for (byte b : hashed) {
        builder.append(String.format("%02x", b));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new BaseException(ErrorCode.INTERNAL, "SHA-256 is not available.", e);
    }
  }
}
