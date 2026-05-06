package io.unitycatalog.server.service.lance;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.service.lance.backend.LanceExecutionBackend;
import io.unitycatalog.server.service.lance.backend.LanceExecutionCommand;
import io.unitycatalog.server.service.lance.backend.LanceExecutionContext;
import io.unitycatalog.server.service.lance.backend.LanceExecutionResult;
import io.unitycatalog.server.service.lance.backend.LanceStorageBinding;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

class LanceDataPlaneService {
  private final LanceExecutionBackend backend;
  private final LanceTableResolver tableResolver;
  private final LanceStorageOptionsService storageOptionsService;

  LanceDataPlaneService(
      LanceExecutionBackend backend,
      LanceTableResolver tableResolver,
      LanceStorageOptionsService storageOptionsService) {
    this.backend = backend;
    this.tableResolver = tableResolver;
    this.storageOptionsService = storageOptionsService;
  }

  LanceExecutionResult query(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.query(command("query", id, delimiter, context, attributes, false));
  }

  LanceExecutionResult countRows(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.countRows(command("count_rows", id, delimiter, context, attributes, false));
  }

  LanceExecutionResult stats(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.stats(command("stats", id, delimiter, context, attributes, false));
  }

  LanceExecutionResult insert(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.insert(command("insert", id, delimiter, context, attributes, true));
  }

  LanceExecutionResult mergeInsert(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.mergeInsert(command("merge_insert", id, delimiter, context, attributes, true));
  }

  LanceExecutionResult update(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.update(command("update", id, delimiter, context, attributes, true));
  }

  LanceExecutionResult delete(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.delete(command("delete", id, delimiter, context, attributes, true));
  }

  LanceExecutionResult explainPlan(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.explainPlan(command("explain_plan", id, delimiter, context, attributes, false));
  }

  LanceExecutionResult analyzePlan(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.analyzePlan(command("analyze_plan", id, delimiter, context, attributes, false));
  }

  LanceExecutionResult create(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.create(command("create", id, delimiter, context, attributes, true));
  }

  private LanceExecutionCommand command(
      String operation,
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes,
      boolean writeOperation) {
    ResolvedLanceTable table = tableResolver.resolve(id, delimiter.orElse(null));
    validateState(operation, table, context, writeOperation);
    LanceStorageBinding storage = storageOptionsService.bindStorage(table);

    Map<String, Object> commandAttributes = new LinkedHashMap<>(attributes);
    commandAttributes.put("materializeDeclaredTable", table.tableRef().declaredOnly());
    commandAttributes.put("legacyBridge", table.tableRef().legacyBridge());
    return new LanceExecutionCommand(
        operation, context, table.tableRef(), storage, commandAttributes);
  }

  private void validateState(
      String operation,
      ResolvedLanceTable table,
      LanceExecutionContext context,
      boolean writeOperation) {
    if (table.legacyBridge()) {
      if (writeOperation) {
        throw new BaseException(
            ErrorCode.UNIMPLEMENTED,
            "Legacy bridge Lance table writes require migration before data operations.");
      }
      if (!legacyReadEnabled(context)) {
        throw new BaseException(
            ErrorCode.UNIMPLEMENTED,
            "Legacy bridge Lance table reads are disabled by default for data operations.");
      }
      return;
    }

    if (table.tableRef().declaredOnly() && !writeOperation) {
      throw new BaseException(
          ErrorCode.ABORTED,
          "Declared Lance table must be materialized before " + operation + " can run.");
    }
  }

  private boolean legacyReadEnabled(LanceExecutionContext context) {
    return context.lanceContext().entrySet().stream()
        .anyMatch(
            entry ->
                "legacyReadEnabled".equals(entry.getKey())
                    && "true".equalsIgnoreCase(entry.getValue()));
  }
}
