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

  private Throwable unwrap(Throwable cause) {
    if (cause instanceof CompletionException && cause.getCause() != null) {
      return cause.getCause();
    }
    return cause;
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
