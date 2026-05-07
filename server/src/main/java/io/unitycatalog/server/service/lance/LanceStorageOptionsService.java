package io.unitycatalog.server.service.lance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.service.lance.backend.LanceExecutionContext;
import io.unitycatalog.server.service.lance.backend.LanceStorageBinding;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class LanceStorageOptionsService {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final long FAKE_CREDENTIAL_TTL_MILLIS = 60 * 60 * 1000L;
  private static final List<String> SENSITIVE_STORAGE_OPTION_FRAGMENTS =
      List.of("token", "session", "secret", "expires", "access_key");

  private final boolean testCredentialVendingEnabled;

  LanceStorageOptionsService(boolean testCredentialVendingEnabled) {
    this.testCredentialVendingEnabled = testCredentialVendingEnabled;
  }

  LanceStorageBinding bindStorage(ResolvedLanceTable table, LanceExecutionContext context) {
    Map<String, String> storageOptionsTemplate = parseStorageOptionsTemplate(table.tableDAO());
    RuntimeStorageOptions runtimeOptions = runtimeStorageOptions(context);
    Map<String, String> storageOptions = new LinkedHashMap<>(storageOptionsTemplate);
    storageOptions.putAll(runtimeOptions.options());
    return new LanceStorageBinding(
        table.tableRef().storageLocation(),
        table.tableRef().storageLocation(),
        table.tableRef().tableUri(),
        storageOptions,
        storageOptionsTemplate,
        runtimeOptions.vended(),
        runtimeOptions.expiresAtMillis());
  }

  private Map<String, String> parseStorageOptionsTemplate(LanceTableDAO tableDAO) {
    if (tableDAO == null
        || tableDAO.getStorageOptionsTemplateJson() == null
        || tableDAO.getStorageOptionsTemplateJson().isBlank()) {
      return Map.of();
    }
    try {
      Map<?, ?> raw = OBJECT_MAPPER.readValue(tableDAO.getStorageOptionsTemplateJson(), Map.class);
      return sanitizeStorageOptionsTemplate(
          raw.entrySet().stream()
              .collect(
                  Collectors.toMap(
                      entry -> String.valueOf(entry.getKey()),
                      entry -> String.valueOf(entry.getValue()))));
    } catch (JsonProcessingException e) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT, "Invalid persisted storage_options_template");
    }
  }

  private Map<String, String> sanitizeStorageOptionsTemplate(
      Map<String, String> storageOptionsTemplate) {
    if (storageOptionsTemplate == null || storageOptionsTemplate.isEmpty()) {
      return Map.of();
    }
    return storageOptionsTemplate.entrySet().stream()
        .filter(entry -> !isSensitiveStorageOption(entry.getKey()))
        .collect(
            Collectors.toMap(
                Map.Entry::getKey, entry -> entry.getValue() == null ? "" : entry.getValue()));
  }

  private boolean isSensitiveStorageOption(String key) {
    String normalizedKey = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
    return SENSITIVE_STORAGE_OPTION_FRAGMENTS.stream().anyMatch(normalizedKey::contains);
  }

  private RuntimeStorageOptions runtimeStorageOptions(LanceExecutionContext context) {
    if (!testCredentialVendingEnabled) {
      return RuntimeStorageOptions.empty();
    }
    Map<String, String> lanceContext = context == null ? Map.of() : context.lanceContext();
    if (truthy(lanceContext.get("fakeStorageDenied"))) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "Storage credential access denied.");
    }
    if (truthy(lanceContext.get("fakeRuntimeCredentialExpired"))) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "Runtime storage credentials expired.");
    }

    Map<String, String> runtimeOptions = new LinkedHashMap<>();
    String fakeCredential = lanceContext.get("fakeRuntimeCredential");
    if (fakeCredential != null && !fakeCredential.isBlank()) {
      runtimeOptions.put("session_token", fakeCredential);
    }
    String fakeRegion = lanceContext.get("fakeRuntimeRegion");
    if (fakeRegion != null && !fakeRegion.isBlank()) {
      runtimeOptions.put("region", fakeRegion);
    }
    return runtimeOptions.isEmpty()
        ? RuntimeStorageOptions.empty()
        : new RuntimeStorageOptions(
            runtimeOptions, true, System.currentTimeMillis() + FAKE_CREDENTIAL_TTL_MILLIS);
  }

  private boolean truthy(String value) {
    return value != null && ("true".equalsIgnoreCase(value) || "1".equals(value));
  }

  private record RuntimeStorageOptions(
      Map<String, String> options, boolean vended, long expiresAtMillis) {
    private RuntimeStorageOptions {
      options = options == null ? Map.of() : Map.copyOf(options);
    }

    private static RuntimeStorageOptions empty() {
      return new RuntimeStorageOptions(Map.of(), false, 0L);
    }
  }
}
