package io.unitycatalog.server.service.lance.backend;

import java.util.Map;

public record LanceExecutionResult(
    Map<String, Object> payload, byte[] binaryBody, String binaryMediaType) {
  public LanceExecutionResult(Map<String, Object> payload) {
    this(payload, null, null);
  }

  public LanceExecutionResult {
    payload = payload == null ? Map.of() : Map.copyOf(payload);
    binaryBody = binaryBody == null ? null : binaryBody.clone();
  }

  @Override
  public byte[] binaryBody() {
    return binaryBody == null ? null : binaryBody.clone();
  }
}
