package io.unitycatalog.server.service.lance.util;

import com.linecorp.armeria.server.ServiceRequestContext;

public final class LanceHeaderUtil {
  private static final String IDEMPOTENCY_KEY_HEADER = "idempotency-key";

  public static String getIdempotencyKey() {
    ServiceRequestContext ctx = ServiceRequestContext.currentOrNull();
    if (ctx == null) {
      return null;
    }
    return ctx.request().headers().get(IDEMPOTENCY_KEY_HEADER);
  }
}
