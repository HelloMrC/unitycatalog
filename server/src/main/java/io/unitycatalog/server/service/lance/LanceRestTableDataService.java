package io.unitycatalog.server.service.lance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import io.unitycatalog.server.auth.UnityCatalogAuthorizer;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.service.lance.backend.LanceExecutionBackend;
import io.unitycatalog.server.service.lance.backend.LanceExecutionContext;
import io.unitycatalog.server.service.lance.backend.LanceExecutionResult;
import io.unitycatalog.server.service.lance.backend.WorkerHttpLanceExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties;
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
  private static final MediaType ARROW_STREAM =
      MediaType.parse("application/vnd.apache.arrow.stream");
  private static final String REQUEST_ID_HEADER = "x-request-id";
  private static final String IDEMPOTENCY_KEY_HEADER = "idempotency-key";
  private static final String DEADLINE_MS_HEADER = "x-lance-deadline-ms";

  private final LanceExecutionBackend backend;
  private final LanceDataPlaneService dataPlaneService;
  private final LanceReconcileService reconcileService;
  private final ServerProperties serverProperties;
  private final LanceArrowRequestReader arrowRequestReader = new LanceArrowRequestReader();
  private final LanceArrowResponseWriter arrowResponseWriter = new LanceArrowResponseWriter();

  public LanceRestTableDataService(
      Repositories repositories,
      LanceExecutionBackend backend,
      UnityCatalogAuthorizer authorizer,
      ServerProperties serverProperties) {
    this.backend = backend;
    this.serverProperties = serverProperties;
    this.reconcileService = new LanceReconcileService(repositories.getLanceTableRepository());
    this.dataPlaneService =
        new LanceDataPlaneService(
            backend,
            new LanceTableResolver(repositories),
            new LanceStorageOptionsService(
                backend.getClass().getName().endsWith(".LanceTestEchoExecutionBackend")),
            new LanceDataPlaneAuthorizer(repositories, authorizer, serverProperties),
            new LanceDataPlaneMetadataUpdater(repositories.getLanceTableRepository()),
            serverProperties.isLanceExecutionLegacyReadEnabled());
  }

  @Post("/admin/reconcile")
  public HttpResponse reconcile(AggregatedHttpRequest request) {
    validateJsonRequest(request);
    LanceExecutionContext context =
        executionContext(request, serverProperties.getLanceExecutionRequestTimeoutMs());
    return json(
        new LanceExecutionResult(reconcileService.reconcile(jsonBody(request), context)), context);
  }

  @Post("/admin/worker/health")
  public HttpResponse workerHealth(AggregatedHttpRequest request) {
    validateJsonRequest(request);
    LanceExecutionContext context =
        executionContext(request, serverProperties.getLanceExecutionRequestTimeoutMs());
    if (backend instanceof WorkerHttpLanceExecutionBackend workerBackend) {
      WorkerHttpLanceExecutionBackend.WorkerHealthStatus health = workerBackend.health();
      return jsonValue(health.payload(), context, health.status());
    }
    return jsonValue(
        Map.of(
            "worker",
            "unavailable",
            "backendType",
            backend.getClass().getSimpleName(),
            "message",
            "Lance worker health is only available for the worker-http backend."),
        context,
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Post("/v1/table/{id}/query")
  public HttpResponse queryTable(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateJsonRequest(request);
    LanceExecutionContext context =
        executionContext(request, serverProperties.getLanceExecutionQueryTimeoutMs());
    LanceExecutionResult result =
        dataPlaneService.query(id, delimiter, context, queryAttributes(jsonBody(request)));
    if (arrowResponseWriter.acceptsArrow(request)) {
      return arrowResponseWriter.write(request, result, context);
    }
    return json(result, context);
  }

  @Post("/v1/table/{id}/count_rows")
  public HttpResponse countRows(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateJsonRequest(request);
    LanceExecutionContext context =
        executionContext(request, serverProperties.getLanceExecutionQueryTimeoutMs());
    return jsonValue(
        requiredPayloadValue(
            dataPlaneService.countRows(id, delimiter, context, safeBody(jsonBody(request))),
            "count"),
        context);
  }

  @Post("/v1/table/{id}/stats")
  public HttpResponse stats(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateJsonRequest(request);
    LanceExecutionContext context =
        executionContext(request, serverProperties.getLanceExecutionRequestTimeoutMs());
    return json(
        dataPlaneService.stats(id, delimiter, context, safeBody(jsonBody(request))), context);
  }

  @Post("/v1/table/{id}/insert")
  public HttpResponse insert(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateArrowRequest(request);
    LanceExecutionContext context =
        executionContext(request, serverProperties.getLanceExecutionWriteTimeoutMs());
    return json(
        dataPlaneService.insert(id, delimiter, context, arrowAttributes(request, Optional.empty())),
        context);
  }

  @Post("/v1/table/{id}/merge_insert")
  public HttpResponse mergeInsert(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateArrowRequest(request);
    LanceExecutionContext context =
        executionContext(request, serverProperties.getLanceExecutionWriteTimeoutMs());
    return json(
        dataPlaneService.mergeInsert(
            id, delimiter, context, arrowAttributes(request, Optional.of("x-lance-merge-options"))),
        context);
  }

  @Post("/v1/table/{id}/update")
  public HttpResponse update(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateJsonRequest(request);
    LanceExecutionContext context =
        executionContext(request, serverProperties.getLanceExecutionWriteTimeoutMs());
    return json(
        dataPlaneService.update(id, delimiter, context, safeBody(jsonBody(request))), context);
  }

  @Post("/v1/table/{id}/delete")
  public HttpResponse delete(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateJsonRequest(request);
    LanceExecutionContext context =
        executionContext(request, serverProperties.getLanceExecutionWriteTimeoutMs());
    return json(
        dataPlaneService.delete(id, delimiter, context, safeBody(jsonBody(request))), context);
  }

  @Post("/v1/table/{id}/explain_plan")
  public HttpResponse explainPlan(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateJsonRequest(request);
    LanceExecutionContext context =
        executionContext(request, serverProperties.getLanceExecutionQueryTimeoutMs());
    return jsonValue(
        requiredPayloadValue(
            dataPlaneService.explainPlan(id, delimiter, context, planAttributes(jsonBody(request))),
            "plan"),
        context);
  }

  @Post("/v1/table/{id}/analyze_plan")
  public HttpResponse analyzePlan(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateJsonRequest(request);
    LanceExecutionContext context =
        executionContext(request, serverProperties.getLanceExecutionQueryTimeoutMs());
    return jsonValue(
        requiredPayloadValue(
            dataPlaneService.analyzePlan(id, delimiter, context, planAttributes(jsonBody(request))),
            "plan"),
        context);
  }

  @Post("/v1/table/{id}/create")
  public HttpResponse create(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateArrowRequest(request);
    LanceExecutionContext context =
        executionContext(request, serverProperties.getLanceExecutionWriteTimeoutMs());
    return json(
        dataPlaneService.create(
            id,
            delimiter,
            context,
            arrowAttributes(request, Optional.of("x-lance-create-options"))),
        context);
  }

  private HttpResponse json(LanceExecutionResult result, LanceExecutionContext context) {
    return jsonValue(result.payload(), context);
  }

  private HttpResponse jsonValue(Object value, LanceExecutionContext context) {
    return jsonValue(value, context, HttpStatus.OK);
  }

  private HttpResponse jsonValue(Object value, LanceExecutionContext context, HttpStatus status) {
    ResponseHeaders headers =
        ResponseHeaders.builder(status)
            .contentType(MediaType.JSON_UTF_8)
            .add(REQUEST_ID_HEADER, context.requestId())
            .build();
    return HttpResponse.ofJson(headers, value);
  }

  private Object requiredPayloadValue(LanceExecutionResult result, String field) {
    if (!result.payload().containsKey(field)) {
      throw new BaseException(ErrorCode.INTERNAL, "Lance backend response missing " + field + ".");
    }
    return result.payload().get(field);
  }

  private void validateJsonRequest(AggregatedHttpRequest request) {
    MediaType contentType = request.contentType();
    if (contentType == null || !contentType.isJson()) {
      throw unsupportedMediaType("application/json", contentType);
    }
    validateBodySize(
        "JSON",
        request.content().length(),
        serverProperties.getLanceExecutionMaxJsonRequestBytes());
  }

  private void validateArrowRequest(AggregatedHttpRequest request) {
    MediaType contentType = request.contentType();
    if (contentType == null || !ARROW_STREAM.equals(contentType.withoutParameters())) {
      throw unsupportedMediaType(ARROW_STREAM.toString(), contentType);
    }
    validateBodySize(
        "Arrow",
        request.content().length(),
        serverProperties.getLanceExecutionMaxArrowRequestBytes());
  }

  private void validateBodySize(String bodyType, int actualBytes, int maxBytes) {
    if (actualBytes > maxBytes) {
      throw new LanceProtocolException(
          HttpStatus.REQUEST_ENTITY_TOO_LARGE,
          "request_entity_too_large",
          bodyType + " request body exceeds " + maxBytes + " bytes.");
    }
  }

  private LanceProtocolException unsupportedMediaType(String expected, MediaType actual) {
    String actualValue = actual == null ? "missing" : actual.toString();
    return new LanceProtocolException(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "unsupported_media_type",
        "Expected Content-Type " + expected + " but received " + actualValue + ".");
  }

  private LanceExecutionContext executionContext(
      AggregatedHttpRequest request, long defaultDeadlineMs) {
    String idempotencyKey = request.headers().get(IDEMPOTENCY_KEY_HEADER);
    return new LanceExecutionContext(
        requestId(request),
        LanceRequestContext.currentPrincipal(),
        authType(request),
        deadlineMs(request, defaultDeadlineMs),
        context(LanceRequestContext.currentContextHeaders()),
        idempotencyKey == null || idempotencyKey.isBlank() ? null : sha256(idempotencyKey));
  }

  private long deadlineMs(AggregatedHttpRequest request, long defaultDeadlineMs) {
    String deadline = request.headers().get(DEADLINE_MS_HEADER);
    if (deadline == null || deadline.isBlank()) {
      return defaultDeadlineMs;
    }
    try {
      long parsed = Long.parseLong(deadline);
      if (parsed > 0) {
        return parsed;
      }
    } catch (NumberFormatException ignored) {
      // Fall through to the stable Lance error below.
    }
    throw new LanceProtocolException(
        HttpStatus.BAD_REQUEST,
        "invalid_deadline",
        DEADLINE_MS_HEADER + " must be a positive integer.");
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
    Map<String, Object> attributes = arrowRequestReader.read(request);
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
    String resolved =
        requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    LanceRequestContext.setCurrentRequestId(resolved);
    return resolved;
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
