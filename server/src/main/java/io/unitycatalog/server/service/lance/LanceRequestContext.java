package io.unitycatalog.server.service.lance;

import com.linecorp.armeria.server.ServiceRequestContext;
import io.netty.util.AttributeKey;

public final class LanceRequestContext {
  static final AttributeKey<String> PRINCIPAL_ATTR =
      AttributeKey.valueOf(String.class, "LANCE_PRINCIPAL_ATTR");

  private LanceRequestContext() {}

  static String currentPrincipal() {
    ServiceRequestContext context = ServiceRequestContext.currentOrNull();
    return context == null ? null : context.attr(PRINCIPAL_ATTR);
  }
}
