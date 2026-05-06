package io.unitycatalog.server.service.lance.backend;

import java.util.LinkedHashMap;
import java.util.Map;

public class LanceTestEchoExecutionBackend implements LanceExecutionBackend {

  @Override
  public LanceExecutionResult query(LanceExecutionCommand command) {
    return result(command, Map.of("arrow", "test-arrow-payload"));
  }

  @Override
  public LanceExecutionResult countRows(LanceExecutionCommand command) {
    return result(command, Map.of("count", 0));
  }

  @Override
  public LanceExecutionResult stats(LanceExecutionCommand command) {
    return result(
        command, Map.of("totalBytes", 0, "numRows", 0, "numIndices", 0, "fragmentStats", Map.of()));
  }

  @Override
  public LanceExecutionResult insert(LanceExecutionCommand command) {
    return result(command, writePayload("insert"));
  }

  @Override
  public LanceExecutionResult mergeInsert(LanceExecutionCommand command) {
    return result(command, Map.of("updatedRows", 0, "insertedRows", 0, "deletedRows", 0));
  }

  @Override
  public LanceExecutionResult update(LanceExecutionCommand command) {
    return result(command, Map.of("updatedRows", 0));
  }

  @Override
  public LanceExecutionResult delete(LanceExecutionCommand command) {
    return result(command, Map.of("deletedRows", 0));
  }

  @Override
  public LanceExecutionResult explainPlan(LanceExecutionCommand command) {
    return result(command, Map.of("plan", "test explain plan"));
  }

  @Override
  public LanceExecutionResult analyzePlan(LanceExecutionCommand command) {
    return result(command, Map.of("plan", "test analyze plan"));
  }

  @Override
  public LanceExecutionResult create(LanceExecutionCommand command) {
    return result(command, writePayload("create"));
  }

  private LanceExecutionResult result(LanceExecutionCommand command, Map<String, Object> payload) {
    Map<String, Object> response = new LinkedHashMap<>(payload);
    response.put("command", commandPayload(command));
    response.put("backendType", "test-echo");
    return new LanceExecutionResult(response);
  }

  private Map<String, Object> commandPayload(LanceExecutionCommand command) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("operation", command.operation());
    payload.put("context", command.context());
    payload.put("table", command.table());
    payload.put("storage", command.storage());
    payload.put("requestId", command.context().requestId());
    payload.put("principal", command.context().principal());
    payload.put("authType", command.context().authType());
    payload.put("idempotencyKeyHash", command.context().idempotencyKeyHash());
    payload.put("tableId", command.table().id());
    payload.put("pathKey", command.table().pathKey());
    payload.put("legacyBridge", command.table().legacyBridge());
    payload.putAll(command.attributes());
    return payload;
  }

  private Map<String, Object> writePayload(String operation) {
    return Map.of(
        "transactionId",
        "test-" + operation + "-transaction",
        "version",
        1,
        "arrow_schema_json",
        "{}",
        "stats",
        Map.of());
  }
}
