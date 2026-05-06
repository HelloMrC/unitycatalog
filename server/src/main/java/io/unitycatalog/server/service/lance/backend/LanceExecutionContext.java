package io.unitycatalog.server.service.lance.backend;

import java.util.Map;

public record LanceExecutionContext(
    String requestId,
    String principal,
    String authType,
    Long deadlineMs,
    Map<String, String> lanceContext,
    String idempotencyKeyHash) {
  public LanceExecutionContext {
    lanceContext = lanceContext == null ? Map.of() : Map.copyOf(lanceContext);
  }
}
