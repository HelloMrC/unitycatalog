package io.unitycatalog.server.service.lance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.LanceTableRepository;
import io.unitycatalog.server.persist.LanceVersionRepository;
import io.unitycatalog.server.service.lance.backend.LanceExecutionContext;
import io.unitycatalog.server.service.lance.backend.LanceExecutionResult;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LanceDataPlaneMetadataUpdater {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final LanceTableRepository tableRepository;
  private final LanceVersionRepository versionRepository;

  LanceDataPlaneMetadataUpdater(
      LanceTableRepository tableRepository, LanceVersionRepository versionRepository) {
    this.tableRepository = tableRepository;
    this.versionRepository = versionRepository;
  }

  LanceExecutionResult afterWrite(
      String operation,
      ResolvedLanceTable table,
      LanceExecutionContext context,
      LanceExecutionResult result) {
    if (table.legacyBridge()) {
      return result;
    }

    Map<String, Object> payload = result.payload();
    try {
      Long returnedVersion = longValue(payload.get("version"));
      if (!table.tableRef().declaredOnly()
          && isVersionRollback(table.tableDAO().getCurrentVersion(), returnedVersion)) {
        // A stale worker response must not move UC's tracked Lance version backwards.
        return versionRollbackResult(
            result, table.tableDAO().getCurrentVersion(), returnedVersion);
      }
      if (table.tableRef().declaredOnly()) {
        // First successful write/create materializes a declared-only metadata entry into an active
        // Lance table while preserving the original declared storage location if the worker does
        // not return one.
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
            returnedVersion,
            statsJson(payload),
            updatedBy(table, context));
      } else {
        // For active tables, the backend remains the source of truth for physical version/schema
        // details; UC records only the fields returned by the worker.
        tableRepository.updateTableExecutionMetadata(
            table.assetDAO().getId(),
            returnedVersion,
            firstString(payload, "arrow_schema_json", "arrowSchemaJson"),
            statsJson(payload),
            updatedBy(table, context));
      }
      recordVersionMetadata(operation, table, context, payload, returnedVersion);
    } catch (RuntimeException e) {
      // At this point the worker reported success. Surface the split-brain risk explicitly so the
      // admin reconcile endpoint can be used instead of hiding it as an ordinary 500.
      throw new LanceBackendCommittedException(
          "Lance backend write completed but UC metadata update failed.", e);
    }
    return result;
  }

  private void recordVersionMetadata(
      String operation,
      ResolvedLanceTable table,
      LanceExecutionContext context,
      Map<String, Object> payload,
      Long returnedVersion) {
    if (returnedVersion == null) {
      return;
    }
    versionRepository.upsertVersion(
        table.assetDAO().getId(),
        returnedVersion,
        operation,
        new Date(),
        firstString(payload, "manifest_path", "manifestPath"),
        firstLong(payload, "manifest_size", "manifestSize"),
        firstString(payload, "etag"),
        firstString(payload, "metadata_json", "metadataJson"),
        statsJson(payload),
        updatedBy(table, context));
  }

  private boolean isVersionRollback(Long currentVersion, Long returnedVersion) {
    return currentVersion != null && returnedVersion != null && returnedVersion < currentVersion;
  }

  private LanceExecutionResult versionRollbackResult(
      LanceExecutionResult result, Long currentVersion, Long returnedVersion) {
    Map<String, Object> payload = new LinkedHashMap<>(result.payload());
    payload.put("metadataVersionUpdated", false);
    payload.put("currentVersion", currentVersion);
    payload.put(
        "warnings",
        List.of(
            "Backend returned version "
                + returnedVersion
                + " below current UC version "
                + currentVersion
                + "; metadata was not updated."));
    return new LanceExecutionResult(payload);
  }

  LanceExecutionResult afterStats(
      ResolvedLanceTable table, LanceExecutionContext context, LanceExecutionResult result) {
    if (!table.legacyBridge()) {
      // Stats are a cache of worker output for metadata visibility; they are not used to answer
      // predicate-aware count_rows requests.
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

  private Long firstLong(Map<String, Object> payload, String... keys) {
    for (String key : keys) {
      Long value = longValue(payload.get(key));
      if (value != null) {
        return value;
      }
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
