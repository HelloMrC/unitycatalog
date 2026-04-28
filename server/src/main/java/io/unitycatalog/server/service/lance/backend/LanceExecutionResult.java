package io.unitycatalog.server.service.lance.backend;

import java.util.Map;

public record LanceExecutionResult(Map<String, Object> payload) {
  public LanceExecutionResult {
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }
}
