package io.unitycatalog.server.service.lance;

import com.linecorp.armeria.common.HttpResponse;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.BaseExceptionHandler;
import java.util.HashMap;
import java.util.Map;

public class LanceExceptionHandler extends BaseExceptionHandler {

  @Override
  protected HttpResponse createErrorResponse(BaseException exception) {
    Map<String, Object> response = new HashMap<>();
    response.put("type", exception.getErrorCode().name().toLowerCase());
    response.put("message", exception.getErrorMessage());
    response.put("code", exception.getErrorCode().name());
    return HttpResponse.ofJson(exception.getErrorCode().getHttpStatus(), response);
  }
}
