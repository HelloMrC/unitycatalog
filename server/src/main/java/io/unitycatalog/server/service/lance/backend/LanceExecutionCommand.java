package io.unitycatalog.server.service.lance.backend;

import java.util.Map;

public record LanceExecutionCommand(
    String operation,
    LanceExecutionContext context,
    LanceTableRef table,
    LanceStorageBinding storage,
    Map<String, Object> attributes,
    byte[] binaryBody) {
  public LanceExecutionCommand(
      String operation,
      LanceExecutionContext context,
      LanceTableRef table,
      LanceStorageBinding storage,
      Map<String, Object> attributes) {
    this(operation, context, table, storage, attributes, null);
  }

  public LanceExecutionCommand {
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    binaryBody = binaryBody == null ? null : binaryBody.clone();
  }

  @Override
  public byte[] binaryBody() {
    return binaryBody == null ? null : binaryBody.clone();
  }
}
