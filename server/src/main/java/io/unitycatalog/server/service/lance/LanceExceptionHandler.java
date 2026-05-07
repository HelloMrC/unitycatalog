package io.unitycatalog.server.service.lance;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.BaseExceptionHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;

public class LanceExceptionHandler extends BaseExceptionHandler {

  @Override
  public HttpResponse handleException(
      ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
    Throwable unwrapped = unwrap(cause);
    if (unwrapped instanceof LanceProtocolException exception) {
      return createProtocolErrorResponse(exception);
    }
    if (unwrapped instanceof LanceObservedException exception) {
      return createObservedErrorResponse(exception);
    }
    if (unwrapped instanceof LanceBackendCommittedException exception) {
      return createBackendCommittedErrorResponse(exception);
    }
    return super.handleException(ctx, req, unwrapped);
  }

  @Override
  protected HttpResponse createErrorResponse(BaseException exception) {
    Map<String, Object> response = new HashMap<>();
    response.put("type", exception.getErrorCode().name().toLowerCase());
    response.put("message", exception.getErrorMessage());
    response.put("code", exception.getErrorCode().getHttpStatus().code());
    return HttpResponse.ofJson(exception.getErrorCode().getHttpStatus(), response);
  }

  private HttpResponse createProtocolErrorResponse(LanceProtocolException exception) {
    Map<String, Object> response = new HashMap<>();
    response.put("type", exception.type());
    response.put("message", exception.getMessage());
    response.put("code", exception.status().code());
    return HttpResponse.ofJson(exception.status(), response);
  }

  private HttpResponse createBackendCommittedErrorResponse(
      LanceBackendCommittedException exception) {
    Map<String, Object> response = new HashMap<>();
    response.put("type", exception.type());
    response.put("message", exception.getMessage());
    response.put("code", exception.status().code());
    response.put("backend_committed", true);
    response.put("reconcileRequired", true);
    return HttpResponse.ofJson(exception.status(), response);
  }

  private HttpResponse createObservedErrorResponse(LanceObservedException exception) {
    Map<String, Object> response = new HashMap<>();
    response.put("type", exception.type());
    response.put("message", exception.getMessage());
    response.put("code", exception.status().code());
    response.put("audit", exception.audit());
    response.put("metrics", exception.metrics());
    if (exception.backendCommitted()) {
      response.put("backend_committed", true);
      response.put("reconcileRequired", exception.reconcileRequired());
    }
    return HttpResponse.ofJson(exception.status(), response);
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

class LanceProtocolException extends RuntimeException {
  private final HttpStatus status;
  private final String type;

  LanceProtocolException(HttpStatus status, String type, String message) {
    super(message);
    this.status = status;
    this.type = type;
  }

  HttpStatus status() {
    return status;
  }

  String type() {
    return type;
  }
}
