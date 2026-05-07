package io.unitycatalog.server.service.lance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.service.lance.backend.LanceStorageBinding;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class LanceStorageOptionsService {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final List<String> SENSITIVE_STORAGE_OPTION_FRAGMENTS =
      List.of("token", "session", "secret", "expires", "access_key");

  LanceStorageBinding bindStorage(ResolvedLanceTable table) {
    Map<String, String> storageOptionsTemplate = parseStorageOptionsTemplate(table.tableDAO());
    return new LanceStorageBinding(
        table.tableRef().storageLocation(),
        table.tableRef().storageLocation(),
        table.tableRef().tableUri(),
        storageOptionsTemplate,
        storageOptionsTemplate,
        false,
        0L);
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
}
