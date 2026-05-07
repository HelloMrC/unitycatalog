package io.unitycatalog.server.service.lance;

import io.unitycatalog.server.service.lance.backend.LanceBackendException;
import io.unitycatalog.server.service.lance.backend.LanceExecutionCommand;
import io.unitycatalog.server.service.lance.backend.LanceExecutionContext;
import io.unitycatalog.server.service.lance.backend.LanceExecutionResult;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class LanceDataPlaneObservability {
  private static final ConcurrentMap<MetricKey, AtomicLong> REQUESTS_TOTAL =
      new ConcurrentHashMap<>();
  private static final ConcurrentMap<MetricKey, AtomicLong> REQUEST_LATENCY_MS =
      new ConcurrentHashMap<>();
  private static final ConcurrentMap<MetricKey, AtomicLong> BACKEND_LATENCY_MS =
      new ConcurrentHashMap<>();
  private static final ConcurrentMap<MetricKey, AtomicLong> ARROW_REQUEST_BYTES =
      new ConcurrentHashMap<>();
  private static final ConcurrentMap<MetricKey, AtomicLong> ARROW_RESPONSE_BYTES =
      new ConcurrentHashMap<>();
  private static final ConcurrentMap<MetricKey, AtomicLong> BACKEND_ERRORS_TOTAL =
      new ConcurrentHashMap<>();
  private static final ConcurrentMap<MetricKey, AtomicLong> METADATA_UPDATE_FAILURES_TOTAL =
      new ConcurrentHashMap<>();

  LanceExecutionResult observe(
      LanceExecutionCommand command,
      ResolvedLanceTable table,
      Supplier<LanceExecutionResult> action) {
    long startedNanos = System.nanoTime();
    try {
      LanceExecutionResult result = action.get();
      long latencyMs = latencyMs(startedNanos);
      Map<String, Object> audit =
          audit(command, table, result.payload(), "success", null, null, latencyMs);
      recordMetrics(
          command.operation(), "success", backendType(result.payload()), latencyMs, audit);
      return observedResult(result, audit);
    } catch (LanceBackendCommittedException e) {
      long latencyMs = latencyMs(startedNanos);
      Map<String, Object> audit =
          audit(command, table, Map.of(), "failure", e.type(), null, latencyMs);
      Map<String, Object> metrics =
          recordMetrics(command.operation(), "failure", "unknown", latencyMs, audit);
      increment(
          METADATA_UPDATE_FAILURES_TOTAL, metricKey(command.operation(), "failure", "unknown"));
      throw new LanceObservedException(
          e.status(), e.type(), e.getMessage(), audit, metrics, true, true, e);
    } catch (LanceBackendException e) {
      long latencyMs = latencyMs(startedNanos);
      Map<String, Object> audit =
          audit(command, table, Map.of(), "failure", e.type(), e.backendRequestId(), latencyMs);
      Map<String, Object> metrics =
          recordMetrics(command.operation(), "failure", "test-echo", latencyMs, audit);
      increment(
          BACKEND_ERRORS_TOTAL, metricKey(command.operation(), "failure", "test-echo"));
      throw new LanceObservedException(
          e.status(), e.type(), e.getMessage(), audit, metrics, false, false, e);
    }
  }

  public static String metricsText() {
    StringBuilder builder = new StringBuilder();
    appendCounters(builder, "lance_data_requests_total", REQUESTS_TOTAL);
    appendCounters(builder, "lance_data_request_latency_ms", REQUEST_LATENCY_MS);
    appendCounters(builder, "lance_data_backend_latency_ms", BACKEND_LATENCY_MS);
    appendCounters(builder, "lance_data_arrow_request_bytes", ARROW_REQUEST_BYTES);
    appendCounters(builder, "lance_data_arrow_response_bytes", ARROW_RESPONSE_BYTES);
    appendCounters(builder, "lance_data_backend_errors_total", BACKEND_ERRORS_TOTAL);
    appendCounters(
        builder, "lance_data_metadata_update_failures_total", METADATA_UPDATE_FAILURES_TOTAL);
    return builder.toString();
  }

  private LanceExecutionResult observedResult(
      LanceExecutionResult result, Map<String, Object> audit) {
    Map<String, Object> payload = new LinkedHashMap<>(result.payload());
    payload.put("audit", audit);
    payload.put(
        "metrics",
        Map.of(
            "operation", audit.get("operation"),
            "status", audit.get("status"),
            "backend", audit.get("backendType"),
            "latencyMs", audit.get("latencyMs")));
    return new LanceExecutionResult(payload);
  }

  private Map<String, Object> audit(
      LanceExecutionCommand command,
      ResolvedLanceTable table,
      Map<String, Object> payload,
      String status,
      String errorCode,
      String backendRequestId,
      long latencyMs) {
    LanceExecutionContext context = command.context();
    Map<String, Object> audit = new LinkedHashMap<>();
    audit.put("requestId", context.requestId());
    audit.put("principal", context.principal());
    audit.put("authType", context.authType());
    audit.put(
        "tableAssetId",
        table.assetDAO() == null ? null : String.valueOf(table.assetDAO().getId()));
    audit.put("tableIdentifier", command.table().pathKey());
    audit.put("operation", command.operation());
    audit.put("backendType", backendType(payload));
    audit.put("workerRequestId", backendRequestId);
    audit.put("backendRequestId", backendRequestId);
    audit.put("storageScheme", storageScheme(command.table().storageLocation()));
    audit.put("inputBytes", inputBytes(command.attributes()));
    audit.put("outputBytes", outputBytes(payload));
    audit.put("tableVersionBefore", command.table().currentVersion());
    audit.put("tableVersionAfter", versionAfter(command, payload));
    audit.put("rowsAffected", rowsAffected(payload));
    audit.put("status", status);
    audit.put("errorCode", errorCode);
    audit.put("latencyMs", latencyMs);
    return audit;
  }

  private Map<String, Object> recordMetrics(
      String operation,
      String status,
      String backend,
      long latencyMs,
      Map<String, Object> audit) {
    MetricKey key = metricKey(operation, status, backend);
    increment(REQUESTS_TOTAL, key);
    add(REQUEST_LATENCY_MS, key, latencyMs);
    add(BACKEND_LATENCY_MS, key, latencyMs);
    add(ARROW_REQUEST_BYTES, key, longValue(audit.get("inputBytes")));
    add(ARROW_RESPONSE_BYTES, key, longValue(audit.get("outputBytes")));
    return Map.of(
        "operation", operation,
        "status", status,
        "backend", backend,
        "latencyMs", latencyMs);
  }

  private static void appendCounters(
      StringBuilder builder, String metricName, ConcurrentMap<MetricKey, AtomicLong> counters) {
    builder.append("# TYPE ").append(metricName).append(" counter\n");
    counters.entrySet().stream()
        .sorted(Comparator.comparing(entry -> entry.getKey().labelKey()))
        .forEach(
            entry ->
                builder
                    .append(metricName)
                    .append(entry.getKey().labels())
                    .append(' ')
                    .append(entry.getValue().get())
                    .append('\n'));
  }

  private static void increment(ConcurrentMap<MetricKey, AtomicLong> counters, MetricKey key) {
    add(counters, key, 1L);
  }

  private static void add(
      ConcurrentMap<MetricKey, AtomicLong> counters, MetricKey key, Long delta) {
    if (delta != null) {
      counters.computeIfAbsent(key, ignored -> new AtomicLong()).addAndGet(delta);
    }
  }

  private static MetricKey metricKey(String operation, String status, String backend) {
    return new MetricKey(operation, status, backend == null ? "unknown" : backend);
  }

  private String backendType(Map<String, Object> payload) {
    Object backendType = payload.get("backendType");
    return backendType == null ? "unknown" : String.valueOf(backendType);
  }

  private Long inputBytes(Map<String, Object> attributes) {
    return longValue(attributes.get("requestBufferedBytes"));
  }

  private Long outputBytes(Map<String, Object> payload) {
    if (payload.isEmpty()) {
      return 0L;
    }
    return (long) payload.toString().getBytes(StandardCharsets.UTF_8).length;
  }

  private Long versionAfter(LanceExecutionCommand command, Map<String, Object> payload) {
    Long version = longValue(payload.get("version"));
    return version == null ? command.table().currentVersion() : version;
  }

  private Long rowsAffected(Map<String, Object> payload) {
    Long rows = firstLong(payload, "rowsAffected", "numRows", "rows");
    if (rows != null) {
      return rows;
    }
    long total = 0L;
    boolean found = false;
    for (String key : new String[] {"insertedRows", "updatedRows", "deletedRows"}) {
      Long value = longValue(payload.get(key));
      if (value != null) {
        total += value;
        found = true;
      }
    }
    return found ? total : null;
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

  private Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String stringValue && !stringValue.isBlank()) {
      return Long.parseLong(stringValue);
    }
    return null;
  }

  private String storageScheme(String location) {
    if (location == null || location.isBlank()) {
      return null;
    }
    int index = location.indexOf("://");
    return index < 0 ? "file" : location.substring(0, index);
  }

  private long latencyMs(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  private record MetricKey(String operation, String status, String backend) {
    private String labels() {
      return "{operation=\""
          + escape(operation)
          + "\",status=\""
          + escape(status)
          + "\",backend=\""
          + escape(backend)
          + "\"}";
    }

    private String labelKey() {
      return operation + ":" + status + ":" + backend;
    }

    private String escape(String value) {
      return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
  }
}
