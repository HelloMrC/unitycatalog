package io.unitycatalog.server.service.lance.backend;

import java.util.Map;

public record LanceStorageBinding(String uri, Map<String, String> storageOptions) {
  public LanceStorageBinding {
    storageOptions = storageOptions == null ? Map.of() : Map.copyOf(storageOptions);
  }
}
