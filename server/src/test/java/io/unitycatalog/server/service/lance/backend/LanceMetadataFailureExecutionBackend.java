package io.unitycatalog.server.service.lance.backend;

import java.util.Map;

public class LanceMetadataFailureExecutionBackend extends LanceTestEchoExecutionBackend {

  @Override
  public LanceExecutionResult insert(LanceExecutionCommand command) {
    return new LanceExecutionResult(
        Map.of(
            "transactionId",
            "test-insert-transaction",
            "version",
            "not-a-version",
            "arrow_schema_json",
            "{}",
            "stats",
            Map.of()));
  }
}
