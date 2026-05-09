package io.unitycatalog.server.service.lance;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.BaseExceptionHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

public class LanceExceptionHandler extends BaseExceptionHandler {
  private static final String REQUEST_ID_HEADER = "x-request-id";

  @Override
  public HttpResponse handleException(
      ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
    Throwable unwrapped = unwrap(cause);
    String requestId = requestId(req);
    if (unwrapped instanceof LanceProtocolException exception) {
      return createProtocolErrorResponse(exception, requestId);
    }
    if (unwrapped instanceof LanceObservedException exception) {
      return createObservedErrorResponse(exception, requestId);
    }
    if (unwrapped instanceof LanceBackendCommittedException exception) {
      return createBackendCommittedErrorResponse(exception, requestId);
    }
    return createErrorResponse(toBaseException(unwrapped), requestId);
  }

  @Override
  protected HttpResponse createErrorResponse(BaseException exception) {
    return createErrorResponse(exception, requestId(null));
  }

  private HttpResponse createErrorResponse(BaseException exception, String requestId) {
    Map<String, Object> response = new HashMap<>();
    response.put("type", exception.getErrorCode().name().toLowerCase());
    response.put("message", exception.getErrorMessage());
    response.put("code", exception.getErrorCode().getHttpStatus().code());
    response.put("requestId", requestId);
    return jsonError(exception.getErrorCode().getHttpStatus(), requestId, response);
  }

  private HttpResponse createProtocolErrorResponse(
      LanceProtocolException exception, String requestId) {
    Map<String, Object> response = new HashMap<>();
    response.put("type", exception.type());
    response.put("message", exception.getMessage());
    response.put("code", exception.status().code());
    response.put("requestId", requestId);
    return jsonError(exception.status(), requestId, response);
  }

  private HttpResponse createBackendCommittedErrorResponse(
      LanceBackendCommittedException exception, String requestId) {
    Map<String, Object> response = new HashMap<>();
    response.put("type", exception.type());
    response.put("message", exception.getMessage());
    response.put("code", exception.status().code());
    response.put("requestId", requestId);
    response.put("backend_committed", true);
    response.put("reconcileRequired", true);
    return jsonError(exception.status(), requestId, response);
  }

  private HttpResponse createObservedErrorResponse(
      LanceObservedException exception, String requestId) {
    Map<String, Object> response = new HashMap<>();
    response.put("type", exception.type());
    response.put("message", exception.getMessage());
    response.put("code", exception.status().code());
    response.put("requestId", requestId);
    putIfPresent(response, "backend_request_id", exception.audit().get("backendRequestId"));
    response.put("audit", exception.audit());
    response.put("metrics", exception.metrics());
    if (exception.backendCommitted()) {
      response.put("backend_committed", true);
      response.put("reconcileRequired", exception.reconcileRequired());
    }
    return jsonError(exception.status(), requestId, response);
  }

  private HttpResponse jsonError(
      HttpStatus status, String requestId, Map<String, Object> response) {
    ResponseHeaders headers =
        ResponseHeaders.builder(status)
            .contentType(MediaType.JSON_UTF_8)
            .add(REQUEST_ID_HEADER, requestId)
            .build();
    return HttpResponse.ofJson(headers, response);
  }

  private void putIfPresent(Map<String, Object> response, String key, Object value) {
    if (value != null && !String.valueOf(value).isBlank()) {
      response.put(key, value);
    }
  }

  private String requestId(HttpRequest request) {
    String contextRequestId = LanceRequestContext.currentRequestId();
    if (contextRequestId != null && !contextRequestId.isBlank()) {
      return contextRequestId;
    }
    String headerRequestId = request == null ? null : request.headers().get(REQUEST_ID_HEADER);
    String resolved =
        headerRequestId == null || headerRequestId.isBlank()
            ? UUID.randomUUID().toString()
            : headerRequestId;
    LanceRequestContext.setCurrentRequestId(resolved);
    return resolved;
  }

  private Throwable unwrap(Throwable cause) {
    if (cause instanceof CompletionException && cause.getCause() != null) {
      return cause.getCause();
    }
    return cause;
  }
}

class LanceBackendCommittedException extends RuntimeException {
  private final HttpStatus status;
  private final String type;

  LanceBackendCommittedException(String message, Throwable cause) {
    super(message, cause);
    this.status = HttpStatus.INTERNAL_SERVER_ERROR;
    this.type = "metadata_update_failed";
  }

  HttpStatus status() {
    return status;
  }

  String type() {
    return type;
  }
}

class LanceObservedException extends RuntimeException {
  private final HttpStatus status;
  private final String type;
  private final Map<String, Object> audit;
  private final Map<String, Object> metrics;
  private final boolean backendCommitted;
  private final boolean reconcileRequired;

  LanceObservedException(
      HttpStatus status,
      String type,
      String message,
      Map<String, Object> audit,
      Map<String, Object> metrics,
      boolean backendCommitted,
      boolean reconcileRequired,
      Throwable cause) {
    super(message, cause);
    this.status = status;
    this.type = type;
    this.audit = audit == null ? Map.of() : new HashMap<>(audit);
    this.metrics = metrics == null ? Map.of() : new HashMap<>(metrics);
    this.backendCommitted = backendCommitted;
    this.reconcileRequired = reconcileRequired;
  }

  HttpStatus status() {
    return status;
  }

  String type() {
    return type;
  }

  Map<String, Object> audit() {
    return audit;
  }

  Map<String, Object> metrics() {
    return metrics;
  }

  boolean backendCommitted() {
    return backendCommitted;
  }

  boolean reconcileRequired() {
    return reconcileRequired;
  }
}
