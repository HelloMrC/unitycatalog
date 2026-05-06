package io.unitycatalog.server.service.lance;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.model.Privileges;
import io.unitycatalog.server.service.lance.backend.LanceExecutionBackend;
import io.unitycatalog.server.service.lance.backend.LanceExecutionCommand;
import io.unitycatalog.server.service.lance.backend.LanceExecutionContext;
import io.unitycatalog.server.service.lance.backend.LanceExecutionResult;
import io.unitycatalog.server.service.lance.backend.LanceStorageBinding;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

class LanceDataPlaneService {
  private final LanceExecutionBackend backend;
  private final LanceTableResolver tableResolver;
  private final LanceStorageOptionsService storageOptionsService;
  private final LanceDataPlaneAuthorizer authorizer;

  LanceDataPlaneService(
      LanceExecutionBackend backend,
      LanceTableResolver tableResolver,
      LanceStorageOptionsService storageOptionsService,
      LanceDataPlaneAuthorizer authorizer) {
    this.backend = backend;
    this.tableResolver = tableResolver;
    this.storageOptionsService = storageOptionsService;
    this.authorizer = authorizer;
  }

  LanceExecutionResult query(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.query(
        command("query", id, delimiter, context, attributes, false, AuthorizationScope.DATA_READ));
  }

  LanceExecutionResult countRows(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.countRows(
        command(
            "count_rows", id, delimiter, context, attributes, false, AuthorizationScope.DATA_READ));
  }

  LanceExecutionResult stats(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.stats(
        command(
            "stats", id, delimiter, context, attributes, false, AuthorizationScope.METADATA_READ));
  }

  LanceExecutionResult insert(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.insert(
        command("insert", id, delimiter, context, attributes, true, AuthorizationScope.DATA_WRITE));
  }

  LanceExecutionResult mergeInsert(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.mergeInsert(
        command(
            "merge_insert",
            id,
            delimiter,
            context,
            attributes,
            true,
            AuthorizationScope.DATA_WRITE));
  }

  LanceExecutionResult update(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.update(
        command("update", id, delimiter, context, attributes, true, AuthorizationScope.DATA_WRITE));
  }

  LanceExecutionResult delete(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.delete(
        command("delete", id, delimiter, context, attributes, true, AuthorizationScope.DATA_WRITE));
  }

  LanceExecutionResult explainPlan(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.explainPlan(
        command(
            "explain_plan",
            id,
            delimiter,
            context,
            attributes,
            false,
            AuthorizationScope.DATA_READ));
  }

  LanceExecutionResult analyzePlan(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.analyzePlan(
        command(
            "analyze_plan",
            id,
            delimiter,
            context,
            attributes,
            false,
            AuthorizationScope.DATA_READ));
  }

  LanceExecutionResult create(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return backend.create(
        command("create", id, delimiter, context, attributes, true, AuthorizationScope.DATA_WRITE));
  }

  private LanceExecutionCommand command(
      String operation,
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes,
      boolean writeOperation,
      AuthorizationScope authorizationScope) {
    ResolvedLanceTable table = tableResolver.resolve(id, delimiter.orElse(null));
    LanceDataPlaneAuthorizer.AuthorizationDecision authorizationDecision =
        authorize(authorizationScope, table);
    validateState(operation, table, context, writeOperation);
    LanceStorageBinding storage = storageOptionsService.bindStorage(table);

    Map<String, Object> commandAttributes = new LinkedHashMap<>(attributes);
    commandAttributes.put("materializeDeclaredTable", table.tableRef().declaredOnly());
    commandAttributes.put("legacyBridge", table.tableRef().legacyBridge());
    commandAttributes.put("requiredPrivilege", authorizationDecision.requiredPrivilege());
    commandAttributes.put(
        "compatiblePrivileges", privilegeNames(authorizationDecision.compatiblePrivileges()));
    return new LanceExecutionCommand(
        operation, context, table.tableRef(), storage, commandAttributes);
  }

  private LanceDataPlaneAuthorizer.AuthorizationDecision authorize(
      AuthorizationScope authorizationScope, ResolvedLanceTable table) {
    return switch (authorizationScope) {
      case DATA_READ -> authorizer.authorizeRead(table);
      case METADATA_READ -> authorizer.authorizeMetadataRead(table);
      case DATA_WRITE -> authorizer.authorizeWrite(table);
    };
  }

  private List<String> privilegeNames(List<Privileges> privileges) {
    return privileges.stream().map(Privileges::name).collect(Collectors.toList());
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

  private enum AuthorizationScope {
    DATA_READ,
    METADATA_READ,
    DATA_WRITE
  }
}
