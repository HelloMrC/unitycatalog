package io.unitycatalog.server.service.lance;

import com.linecorp.armeria.common.HttpRequest;
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
  private final LanceDataPlaneMetadataUpdater metadataUpdater;
  private final LanceDataPlaneObservability observability = new LanceDataPlaneObservability();
  private final boolean legacyReadEnabled;

  LanceDataPlaneService(
      LanceExecutionBackend backend,
      LanceTableResolver tableResolver,
      LanceStorageOptionsService storageOptionsService,
      LanceDataPlaneAuthorizer authorizer,
      LanceDataPlaneMetadataUpdater metadataUpdater,
      boolean legacyReadEnabled) {
    this.backend = backend;
    this.tableResolver = tableResolver;
    this.storageOptionsService = storageOptionsService;
    this.authorizer = authorizer;
    this.metadataUpdater = metadataUpdater;
    this.legacyReadEnabled = legacyReadEnabled;
  }

  LanceExecutionResult query(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    PreparedCommand prepared =
        command("query", id, delimiter, context, attributes, false, AuthorizationScope.DATA_READ);
    return observability.observe(
        prepared.command(), prepared.table(), () -> backend.query(prepared.command()));
  }

  LanceExecutionResult countRows(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    PreparedCommand prepared =
        command(
            "count_rows", id, delimiter, context, attributes, false, AuthorizationScope.DATA_READ);
    return observability.observe(
        prepared.command(), prepared.table(), () -> backend.countRows(prepared.command()));
  }

  LanceExecutionResult stats(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    PreparedCommand prepared =
        command(
            "stats", id, delimiter, context, attributes, false, AuthorizationScope.METADATA_READ);
    return observability.observe(
        prepared.command(),
        prepared.table(),
        () -> metadataUpdater.afterStats(
            prepared.table(), context, backend.stats(prepared.command())));
  }

  LanceExecutionResult insert(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return insert(id, delimiter, context, attributes, null);
  }

  LanceExecutionResult insert(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes,
      HttpRequest binaryRequest) {
    PreparedCommand prepared =
        command(
            "insert",
            id,
            delimiter,
            context,
            attributes,
            true,
            AuthorizationScope.DATA_WRITE,
            binaryRequest);
    return observability.observe(
        prepared.command(),
        prepared.table(),
        () -> metadataUpdater.afterWrite(
            "insert", prepared.table(), context, backend.insert(prepared.command())));
  }

  LanceExecutionResult mergeInsert(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return mergeInsert(id, delimiter, context, attributes, null);
  }

  LanceExecutionResult mergeInsert(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes,
      HttpRequest binaryRequest) {
    PreparedCommand prepared =
        command(
            "merge_insert",
            id,
            delimiter,
            context,
            attributes,
            true,
            AuthorizationScope.DATA_WRITE,
            binaryRequest);
    return observability.observe(
        prepared.command(),
        prepared.table(),
        () -> metadataUpdater.afterWrite(
            "merge_insert", prepared.table(), context, backend.mergeInsert(prepared.command())));
  }

  LanceExecutionResult update(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    PreparedCommand prepared =
        command("update", id, delimiter, context, attributes, true, AuthorizationScope.DATA_WRITE);
    return observability.observe(
        prepared.command(),
        prepared.table(),
        () -> metadataUpdater.afterWrite(
            "update", prepared.table(), context, backend.update(prepared.command())));
  }

  LanceExecutionResult delete(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    PreparedCommand prepared =
        command("delete", id, delimiter, context, attributes, true, AuthorizationScope.DATA_WRITE);
    return observability.observe(
        prepared.command(),
        prepared.table(),
        () -> metadataUpdater.afterWrite(
            "delete", prepared.table(), context, backend.delete(prepared.command())));
  }

  LanceExecutionResult explainPlan(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    PreparedCommand prepared =
        command(
            "explain_plan",
            id,
            delimiter,
            context,
            attributes,
            false,
            AuthorizationScope.DATA_READ);
    return observability.observe(
        prepared.command(), prepared.table(), () -> backend.explainPlan(prepared.command()));
  }

  LanceExecutionResult analyzePlan(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    PreparedCommand prepared =
        command(
            "analyze_plan",
            id,
            delimiter,
            context,
            attributes,
            false,
            AuthorizationScope.DATA_READ);
    return observability.observe(
        prepared.command(), prepared.table(), () -> backend.analyzePlan(prepared.command()));
  }

  LanceExecutionResult create(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes) {
    return create(id, delimiter, context, attributes, null);
  }

  LanceExecutionResult create(
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes,
      HttpRequest binaryRequest) {
    PreparedCommand prepared =
        command(
            "create",
            id,
            delimiter,
            context,
            attributes,
            true,
            AuthorizationScope.DATA_WRITE,
            binaryRequest);
    return observability.observe(
        prepared.command(),
        prepared.table(),
        () -> metadataUpdater.afterWrite(
            "create", prepared.table(), context, backend.create(prepared.command())));
  }

  private PreparedCommand command(
      String operation,
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes,
      boolean writeOperation,
      AuthorizationScope authorizationScope) {
    return command(
        operation, id, delimiter, context, attributes, writeOperation, authorizationScope, null);
  }

  private PreparedCommand command(
      String operation,
      String id,
      Optional<String> delimiter,
      LanceExecutionContext context,
      Map<String, Object> attributes,
      boolean writeOperation,
      AuthorizationScope authorizationScope,
      HttpRequest binaryRequest) {
    // Every data-plane operation follows the same boundary: resolve governed metadata, authorize on
    // the UC resource, validate Lance table state, vend runtime storage options, then dispatch.
    ResolvedLanceTable table = tableResolver.resolve(id, delimiter.orElse(null));
    LanceDataPlaneAuthorizer.AuthorizationDecision authorizationDecision =
        authorize(authorizationScope, table);
    validateState(operation, table, writeOperation);
    LanceStorageBinding storage = storageOptionsService.bindStorage(table, context);

    Map<String, Object> commandAttributes = new LinkedHashMap<>(attributes);
    byte[] binaryBody =
        binaryRequest == null
            ? binaryBody(commandAttributes.remove(LanceArrowRequestReader.ARROW_BODY_ATTRIBUTE))
            : null;
    if (binaryRequest != null) {
      commandAttributes.remove(LanceArrowRequestReader.ARROW_BODY_ATTRIBUTE);
    }
    commandAttributes.put("materializeDeclaredTable", table.tableRef().declaredOnly());
    commandAttributes.put("legacyBridge", table.tableRef().legacyBridge());
    commandAttributes.put("requiredPrivilege", authorizationDecision.requiredPrivilege());
    commandAttributes.put(
        "compatiblePrivileges", privilegeNames(authorizationDecision.compatiblePrivileges()));
    return new PreparedCommand(
        new LanceExecutionCommand(
            operation,
            context,
            table.tableRef(),
            storage,
            commandAttributes,
            binaryBody,
            binaryRequest),
        table);
  }

  private byte[] binaryBody(Object value) {
    return value instanceof byte[] bytes ? bytes : null;
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
      boolean writeOperation) {
    if (table.legacyBridge()) {
      // Legacy UC TEXT tables only prove metadata compatibility. Writes would bypass the native
      // uc_lance_* state model, and reads stay opt-in until a worker path is explicitly configured.
      if (writeOperation) {
        throw new BaseException(
            ErrorCode.UNIMPLEMENTED,
            "Legacy bridge Lance table writes require migration before data operations.");
      }
      if (!legacyReadEnabled) {
        throw new BaseException(
            ErrorCode.UNIMPLEMENTED,
            "Legacy bridge Lance table reads are disabled by default for data operations.");
      }
      return;
    }

    if (table.tableRef().declaredOnly() && !writeOperation) {
      // Declared tables have UC metadata but no guaranteed physical Lance dataset yet.
      throw new BaseException(
          ErrorCode.ABORTED,
          "Declared Lance table must be materialized before " + operation + " can run.");
    }
  }

  private enum AuthorizationScope {
    DATA_READ,
    METADATA_READ,
    DATA_WRITE
  }

  private record PreparedCommand(LanceExecutionCommand command, ResolvedLanceTable table) {}
}
