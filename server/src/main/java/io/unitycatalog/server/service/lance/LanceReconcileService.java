package io.unitycatalog.server.service.lance;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.LanceTableRepository;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.service.lance.backend.LanceExecutionContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LanceReconcileService {
  private static final String TABLE_ID = "table_id";
  private static final String DRY_RUN = "dry_run";
  private static final String CURRENT_VERSION = "current_version";
  private static final String ARROW_SCHEMA_JSON = "arrow_schema_json";
  private static final String STATS_JSON = "stats_json";

  private final LanceTableRepository tableRepository;
  private final LanceIdentifierCodec identifierCodec = new LanceIdentifierCodec();

  LanceReconcileService(LanceTableRepository tableRepository) {
    this.tableRepository = tableRepository;
  }

  Map<String, Object> reconcile(Map<String, Object> body, LanceExecutionContext context) {
    String tableId = stringValue(body.get(TABLE_ID));
    if (tableId == null) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "table_id is required.");
    }
    LanceAssetDAO asset = asset(tableId, stringValue(body.get("delimiter")));
    LanceTableDAO table = tableRepository.findTableByAssetId(asset.getId()).orElseThrow();
    Map<String, Object> before = tableSnapshot(table);
    Map<String, Object> plan = reconcilePlan(body, table);
    boolean dryRun = booleanValue(body.get(DRY_RUN), true);
    if (dryRun) {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("status", "DRY_RUN");
      response.put("backend_committed", true);
      response.put("table_id", tableId);
      response.put("plan", plan);
      response.put("audit", audit(context, before, before));
      return response;
    }

    tableRepository.updateTableExecutionMetadata(
        asset.getId(),
        longValue(plan.get(CURRENT_VERSION)),
        stringValue(plan.get(ARROW_SCHEMA_JSON)),
        stringValue(plan.get(STATS_JSON)),
        operator(context));
    LanceTableDAO updated = tableRepository.findTableByAssetId(asset.getId()).orElseThrow();
    Map<String, Object> after = tableSnapshot(updated);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("status", "UPDATED");
    response.put("backend_committed", true);
    response.put("table_id", tableId);
    response.put("plan", plan);
    response.put("audit", audit(context, before, after));
    return response;
  }

  private LanceAssetDAO asset(String tableId, String delimiter) {
    String pathKey =
        identifierCodec.toPathKey(identifierCodec.decodeIdentifier(tableId, delimiter));
    return tableRepository
        .findAssetByPathKey(pathKey)
        .orElseThrow(
            () -> new BaseException(ErrorCode.NOT_FOUND, "Lance table not found: " + tableId));
  }

  private Map<String, Object> reconcilePlan(Map<String, Object> body, LanceTableDAO table) {
    Map<String, Object> plan = new LinkedHashMap<>();
    plan.put(
        CURRENT_VERSION,
        body.containsKey(CURRENT_VERSION)
            ? longValue(body.get(CURRENT_VERSION))
            : nextVersion(table.getCurrentVersion()));
    plan.put(
        ARROW_SCHEMA_JSON,
        firstNonBlank(stringValue(body.get(ARROW_SCHEMA_JSON)), table.getArrowSchemaJson(), "{}"));
    plan.put(
        STATS_JSON, firstNonBlank(stringValue(body.get(STATS_JSON)), table.getStatsJson(), "{}"));
    plan.put("fields", List.of(CURRENT_VERSION, ARROW_SCHEMA_JSON, STATS_JSON));
    return plan;
  }

  private Long nextVersion(Long currentVersion) {
    return currentVersion == null ? 1L : currentVersion + 1L;
  }

  private Map<String, Object> tableSnapshot(LanceTableDAO table) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put(CURRENT_VERSION, table.getCurrentVersion());
    snapshot.put(ARROW_SCHEMA_JSON, table.getArrowSchemaJson());
    snapshot.put(STATS_JSON, table.getStatsJson());
    return snapshot;
  }

  private Map<String, Object> audit(
      LanceExecutionContext context, Map<String, Object> before, Map<String, Object> after) {
    Map<String, Object> audit = new LinkedHashMap<>();
    audit.put("operator", operator(context));
    audit.put("before", before);
    audit.put("after", after);
    return audit;
  }

  private String operator(LanceExecutionContext context) {
    String principal = context.principal();
    return principal == null || principal.isBlank() ? "anonymous" : principal;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private String stringValue(Object value) {
    return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : null;
  }

  private Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String stringValue && !stringValue.isBlank()) {
      return Long.parseLong(stringValue);
    }
    return null;
  }

  private boolean booleanValue(Object value, boolean defaultValue) {
    return value instanceof Boolean booleanValue ? booleanValue : defaultValue;
  }
}
