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

  private final LanceDataPlaneService dataPlaneService;
  private final ServerProperties serverProperties;

  public LanceRestTableDataService(
      Repositories repositories,
      LanceExecutionBackend backend,
      UnityCatalogAuthorizer authorizer,
      ServerProperties serverProperties) {
    this.serverProperties = serverProperties;
    this.dataPlaneService =
        new LanceDataPlaneService(
            backend,
            new LanceTableResolver(repositories),
            new LanceStorageOptionsService(),
            new LanceDataPlaneAuthorizer(repositories, authorizer, serverProperties),
            new LanceDataPlaneMetadataUpdater(repositories.getLanceTableRepository()));
  }

  @Post("/v1/table/{id}/query")
  public HttpResponse queryTable(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateJsonRequest(request);
    LanceExecutionContext context = executionContext(request);
    return json(
        dataPlaneService.query(id, delimiter, context, queryAttributes(jsonBody(request))),
        context);
  }

  @Post("/v1/table/{id}/count_rows")
  public HttpResponse countRows(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateJsonRequest(request);
    LanceExecutionContext context = executionContext(request);
    return json(
        dataPlaneService.countRows(id, delimiter, context, safeBody(jsonBody(request))), context);
  }

  @Post("/v1/table/{id}/stats")
  public HttpResponse stats(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateJsonRequest(request);
    LanceExecutionContext context = executionContext(request);
    return json(
        dataPlaneService.stats(id, delimiter, context, safeBody(jsonBody(request))), context);
  }

  @Post("/v1/table/{id}/insert")
  public HttpResponse insert(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateArrowRequest(request);
    LanceExecutionContext context = executionContext(request);
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
    LanceExecutionContext context = executionContext(request);
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
    LanceExecutionContext context = executionContext(request);
    return json(
        dataPlaneService.update(id, delimiter, context, safeBody(jsonBody(request))), context);
  }

  @Post("/v1/table/{id}/delete")
  public HttpResponse delete(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateJsonRequest(request);
    LanceExecutionContext context = executionContext(request);
    return json(
        dataPlaneService.delete(id, delimiter, context, safeBody(jsonBody(request))), context);
  }

  @Post("/v1/table/{id}/explain_plan")
  public HttpResponse explainPlan(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateJsonRequest(request);
    LanceExecutionContext context = executionContext(request);
    return json(
        dataPlaneService.explainPlan(id, delimiter, context, planAttributes(jsonBody(request))),
        context);
  }

  @Post("/v1/table/{id}/analyze_plan")
  public HttpResponse analyzePlan(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateJsonRequest(request);
    LanceExecutionContext context = executionContext(request);
    return json(
        dataPlaneService.analyzePlan(id, delimiter, context, planAttributes(jsonBody(request))),
        context);
  }

  @Post("/v1/table/{id}/create")
  public HttpResponse create(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateArrowRequest(request);
    LanceExecutionContext context = executionContext(request);
    return json(
        dataPlaneService.create(
            id,
            delimiter,
            context,
            arrowAttributes(request, Optional.of("x-lance-create-options"))),
        context);
  }

  private HttpResponse json(LanceExecutionResult result, LanceExecutionContext context) {
    ResponseHeaders headers =
        ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .add(REQUEST_ID_HEADER, context.requestId())
            .build();
    return HttpResponse.ofJson(headers, result.payload());
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

  private LanceExecutionContext executionContext(AggregatedHttpRequest request) {
    String idempotencyKey = request.headers().get(IDEMPOTENCY_KEY_HEADER);
    return new LanceExecutionContext(
        requestId(request),
        LanceRequestContext.currentPrincipal(),
        authType(request),
        null,
        context(LanceRequestContext.currentContextHeaders()),
        idempotencyKey == null || idempotencyKey.isBlank() ? null : sha256(idempotencyKey));
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
