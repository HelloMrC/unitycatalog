package io.unitycatalog.server.service.lance.backend;

import java.util.List;

public record LanceTableRef(
    String id,
    List<String> namespacePath,
    String tableName,
    String pathKey,
    String storageLocation,
    Long currentVersion,
    boolean declaredOnly,
    boolean legacyBridge) {
  public LanceTableRef {
    namespacePath = namespacePath == null ? List.of() : List.copyOf(namespacePath);
  }
}
