package io.unitycatalog.server.service.lance.backend;

import java.util.Map;

public record LanceExecutionCommand(
    String operation, String tableId, String delimiter, Map<String, Object> attributes) {
  public LanceExecutionCommand {
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
