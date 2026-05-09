package io.unitycatalog.server.service.lance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.Server;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

class LanceMinimalRealWorkerFixture implements AutoCloseable {
  static final String HEALTH_PATH = "/internal/lance/v1/health";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final MediaType ARROW_FILE = MediaType.parse("application/vnd.apache.arrow.file");
  private static final MediaType ARROW_STREAM =
      MediaType.parse("application/vnd.apache.arrow.stream");

  private final ConcurrentMap<String, WorkerTable> tables = new ConcurrentHashMap<>();
  private Server server;
  private String baseUrl;

  void start() {
    server =
        Server.builder()
            .http(0)
            .service(HEALTH_PATH, this::health)
            .serviceUnder("/internal/lance/v1/commands", this::workerResponse)
            .serviceUnder("/internal/lance/v1/arrow", this::workerResponse)
            .build();
    server.start().join();
    baseUrl = "http://127.0.0.1:" + server.activeLocalPort();
  }

  String baseUrl() {
    return baseUrl;
  }

  @Override
  public void close() {
    if (server != null) {
      server.stop().join();
      server = null;
    }
  }

  private HttpResponse health(ServiceRequestContext ctx, HttpRequest req) {
    return HttpResponse.ofJson(
        Map.of("worker", "minimal-real-worker", "status", "ok", "tables", tables.size()));
  }

  private HttpResponse workerResponse(ServiceRequestContext ctx, HttpRequest req) {
    return HttpResponse.of(
        req.aggregate().thenApply(request -> workerResponse(ctx.path(), req.headers(), request)));
  }

  private HttpResponse workerResponse(
      String path, RequestHeaders headers, AggregatedHttpRequest request) {
    Map<String, Object> command = command(headers, request);
    String operation = String.valueOf(command.get("operation"));
    if ("query".equals(operation)) {
      return arrowQuery(command);
    }
    Map<String, Object> response;
    switch (operation) {
      case "insert", "create" -> response = write(command, request.content().length());
      case "merge_insert" -> response = mergeInsert(command, request.content().length());
      case "count_rows" -> response = countRows(command);
      case "stats" -> response = stats(command);
      case "explain_plan" -> response = Map.of("plan", "minimal worker explain plan");
      case "analyze_plan" -> response = Map.of("plan", "minimal worker analyze plan");
      case "update" -> response = update(command);
      case "delete" -> response = delete(command);
      default -> response = Map.of();
    }
    Map<String, Object> payload = new LinkedHashMap<>(response);
    payload.put("backendType", "worker-http");
    payload.put("workerPath", path);
    payload.put("minimalRealWorker", true);
    copyIfPresent(payload, command, "requestBufferedBytes");
    copyIfPresent(payload, command, "requestContentLength");
    copyIfPresent(payload, command, "streamPassedThrough");
    copyIfPresent(payload, command, "ucRequestMode");
    return HttpResponse.ofJson(payload);
  }

  private HttpResponse arrowQuery(Map<String, Object> command) {
    WorkerTable table = table(command);
    byte[] body =
        ("minimal-real-worker|"
                + pathKey(command)
                + "|rows="
                + table.rows.get()
                + "|version="
                + table.version.get())
            .getBytes(StandardCharsets.UTF_8);
    ResponseHeaders headers =
        ResponseHeaders.builder(HttpStatus.OK).contentType(ARROW_FILE).build();
    return HttpResponse.of(headers, HttpData.wrap(body));
  }

  private Map<String, Object> write(Map<String, Object> command, int bytes) {
    WorkerTable table = table(command);
    long version = table.version.incrementAndGet();
    table.rows.incrementAndGet();
    table.bytes.addAndGet(bytes);
    return Map.of(
        "transactionId", "minimal-worker-" + version,
        "version", version,
        "arrowSchemaJson", "{\"fields\":[]}",
        "stats", statsPayload(table),
        "storageLocation", table.storageLocation,
        "tableUri", table.tableUri);
  }

  private Map<String, Object> countRows(Map<String, Object> command) {
    return Map.of("count", table(command).rows.get(), "backendType", "worker-http");
  }

  private Map<String, Object> stats(Map<String, Object> command) {
    return statsPayload(table(command));
  }

  private Map<String, Object> update(Map<String, Object> command) {
    WorkerTable table = table(command);
    long version = table.version.incrementAndGet();
    return Map.of("updatedRows", 1, "version", version, "stats", statsPayload(table));
  }

  private Map<String, Object> mergeInsert(Map<String, Object> command, int bytes) {
    WorkerTable table = table(command);
    long version = table.version.incrementAndGet();
    table.rows.incrementAndGet();
    table.bytes.addAndGet(bytes);
    return Map.of(
        "transactionId",
        "minimal-worker-merge-" + version,
        "version",
        version,
        "updatedRows",
        1,
        "insertedRows",
        1,
        "deletedRows",
        0,
        "stats",
        statsPayload(table));
  }

  private Map<String, Object> delete(Map<String, Object> command) {
    WorkerTable table = table(command);
    long version = table.version.incrementAndGet();
    long deleted = table.rows.getAndSet(0);
    return Map.of("deletedRows", deleted, "version", version, "stats", statsPayload(table));
  }

  private Map<String, Object> statsPayload(WorkerTable table) {
    return Map.of(
        "totalBytes", table.bytes.get(),
        "numRows", table.rows.get(),
        "numIndices", 0,
        "fragmentStats", Map.of());
  }

  private void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
    if (source.containsKey(key)) {
      target.put(key, source.get(key));
    }
  }

  private WorkerTable table(Map<String, Object> command) {
    return tables.computeIfAbsent(pathKey(command), ignored -> new WorkerTable(command));
  }

  @SuppressWarnings("unchecked")
  private String pathKey(Map<String, Object> command) {
    Object table = command.get("table");
    if (table instanceof Map<?, ?> tableMap) {
      Object pathKey = tableMap.get("pathKey");
      if (pathKey != null) {
        return String.valueOf(pathKey);
      }
    }
    return String.valueOf(command.get("pathKey"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> command(RequestHeaders headers, AggregatedHttpRequest request) {
    if (request.contentType() != null
        && ARROW_STREAM.equals(request.contentType().withoutParameters())) {
      Map<String, Object> command = readHeaderJson(headers, "x-uc-lance-attributes");
      Map<String, Object> table = readHeaderJson(headers, "x-uc-lance-table");
      command.put("operation", headers.get("x-uc-lance-command"));
      command.put("context", readHeaderJson(headers, "x-uc-lance-context"));
      command.put("table", table);
      command.put("storage", readHeaderJson(headers, "x-uc-lance-storage"));
      command.put("pathKey", table.get("pathKey"));
      return command;
    }
    return readJson(request.contentUtf8());
  }

  private Map<String, Object> readHeaderJson(RequestHeaders headers, String name) {
    String value = headers.get(name);
    if (value == null || value.isBlank()) {
      return new LinkedHashMap<>();
    }
    byte[] json = Base64.getUrlDecoder().decode(value);
    return readJson(new String(json, StandardCharsets.UTF_8));
  }

  private Map<String, Object> readJson(String content) {
    try {
      return new LinkedHashMap<>(OBJECT_MAPPER.readValue(content, MAP_TYPE));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static class WorkerTable {
    private final AtomicLong version = new AtomicLong();
    private final AtomicLong rows = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final String storageLocation;
    private final String tableUri;

    WorkerTable(Map<String, Object> command) {
      Map<String, Object> table = (Map<String, Object>) command.getOrDefault("table", Map.of());
      storageLocation = String.valueOf(table.getOrDefault("storageLocation", ""));
      tableUri = String.valueOf(table.getOrDefault("tableUri", storageLocation));
    }
  }
}
