package io.unitycatalog.server.service.lance.backend;

import java.util.Map;

public record LanceStorageBinding(
    String uri,
    String storageLocation,
    String tableUri,
    Map<String, String> storageOptions,
    Map<String, String> storageOptionsTemplate,
    boolean vendCredentials,
    long expiresAtMillis) {
  public LanceStorageBinding {
    storageLocation = isBlank(storageLocation) ? uri : storageLocation;
    uri = isBlank(uri) ? storageLocation : uri;
    tableUri = isBlank(tableUri) ? storageLocation : tableUri;
    storageOptions = storageOptions == null ? Map.of() : Map.copyOf(storageOptions);
    storageOptionsTemplate =
        storageOptionsTemplate == null ? Map.of() : Map.copyOf(storageOptionsTemplate);
  }

  public LanceStorageBinding(String uri, Map<String, String> storageOptions) {
    this(uri, uri, uri, storageOptions, storageOptions, false, 0L);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
