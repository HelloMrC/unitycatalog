package io.unitycatalog.server.service.lance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.LanceTableRepository;
import io.unitycatalog.server.service.lance.backend.LanceExecutionContext;
import io.unitycatalog.server.service.lance.backend.LanceExecutionResult;
import java.util.LinkedHashMap;
import java.util.Map;

class LanceDataPlaneMetadataUpdater {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final LanceTableRepository tableRepository;

  LanceDataPlaneMetadataUpdater(LanceTableRepository tableRepository) {
    this.tableRepository = tableRepository;
  }

  LanceExecutionResult afterWrite(
      ResolvedLanceTable table, LanceExecutionContext context, LanceExecutionResult result) {
    if (table.legacyBridge()) {
      return result;
    }

    Map<String, Object> payload = result.payload();
    if (table.tableRef().declaredOnly()) {
      String storageLocation = firstString(payload, "storage_location", "storageLocation");
      if (storageLocation == null) {
        storageLocation = table.tableDAO().getStorageLocation();
      }
      String tableUri = firstString(payload, "table_uri", "tableUri");
      if (tableUri == null) {
        tableUri = table.tableDAO().getTableUri();
      }
      tableRepository.markTableMaterialized(
          table.assetDAO().getId(),
          storageLocation,
          tableUri,
          firstString(payload, "arrow_schema_json", "arrowSchemaJson"),
          longValue(payload.get("version")),
          statsJson(payload),
          updatedBy(table, context));
    } else {
      tableRepository.updateTableExecutionMetadata(
          table.assetDAO().getId(),
          longValue(payload.get("version")),
          firstString(payload, "arrow_schema_json", "arrowSchemaJson"),
          statsJson(payload),
          updatedBy(table, context));
    }
    return result;
  }

  LanceExecutionResult afterStats(
      ResolvedLanceTable table, LanceExecutionContext context, LanceExecutionResult result) {
    if (!table.legacyBridge()) {
      tableRepository.updateTableStats(
          table.assetDAO().getId(), statsPayloadJson(result.payload()), updatedBy(table, context));
    }
    return result;
  }

  private String updatedBy(ResolvedLanceTable table, LanceExecutionContext context) {
    String principal = context.principal();
    if (principal != null && !principal.isBlank()) {
      return principal;
    }
    return table.assetDAO().getOwner();
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

  private String statsPayloadJson(Map<String, Object> payload) {
    String nestedStatsJson = statsJson(payload);
    if (nestedStatsJson != null) {
      return nestedStatsJson;
    }
    Map<String, Object> statsPayload = new LinkedHashMap<>(payload);
    statsPayload.remove("command");
    statsPayload.remove("backendType");
    return statsPayload.isEmpty() ? null : toJson(statsPayload);
  }

  private String statsJson(Map<String, Object> payload) {
    String explicitStatsJson = firstString(payload, "stats_json", "statsJson");
    if (explicitStatsJson != null) {
      return explicitStatsJson;
    }
    Object stats = payload.get("stats");
    if (stats == null) {
      return null;
    }
    return toJson(stats);
  }

  private String toJson(Object value) {
    try {
      return OBJECT_MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new BaseException(ErrorCode.INTERNAL, "Failed to serialize Lance stats.", e);
    }
  }

  private String firstString(Map<String, Object> payload, String... keys) {
    for (String key : keys) {
      Object value = payload.get(key);
      if (value instanceof String stringValue && !stringValue.isBlank()) {
        return stringValue;
      }
    }
    return null;
  }
}
