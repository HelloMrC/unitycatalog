package io.unitycatalog.server.service.lance.backend;

import io.unitycatalog.server.utils.ServerProperties;

public final class LanceExecutionBackendFactory {
  private LanceExecutionBackendFactory() {}

  public static LanceExecutionBackend create(ServerProperties serverProperties) {
    return new DisabledLanceExecutionBackend();
  }
}
