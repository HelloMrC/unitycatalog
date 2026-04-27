package io.unitycatalog.server.service.lance;

import com.linecorp.armeria.server.ServiceRequestContext;
import io.netty.util.AttributeKey;
import java.util.Map;

public final class LanceRequestContext {
  static final AttributeKey<String> PRINCIPAL_ATTR =
      AttributeKey.valueOf(String.class, "LANCE_PRINCIPAL_ATTR");
  static final AttributeKey<Map> CONTEXT_HEADERS_ATTR =
      AttributeKey.valueOf(Map.class, "LANCE_CONTEXT_HEADERS_ATTR");

  private LanceRequestContext() {}

  static String currentPrincipal() {
    ServiceRequestContext context = ServiceRequestContext.currentOrNull();
    return context == null ? null : context.attr(PRINCIPAL_ATTR);
  }

  @SuppressWarnings("unchecked")
  static Map<String, String> currentContextHeaders() {
    ServiceRequestContext context = ServiceRequestContext.currentOrNull();
    if (context == null) {
      return Map.of();
    }
    Map<String, String> headers = context.attr(CONTEXT_HEADERS_ATTR);
    return headers == null ? Map.of() : headers;
  }
}
