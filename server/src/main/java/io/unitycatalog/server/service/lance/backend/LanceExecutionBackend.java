package io.unitycatalog.server.service.lance.backend;

public interface LanceExecutionBackend {
  LanceExecutionResult query(LanceExecutionCommand command);

  LanceExecutionResult countRows(LanceExecutionCommand command);

  LanceExecutionResult stats(LanceExecutionCommand command);

  LanceExecutionResult insert(LanceExecutionCommand command);

  LanceExecutionResult mergeInsert(LanceExecutionCommand command);

  LanceExecutionResult update(LanceExecutionCommand command);

  LanceExecutionResult delete(LanceExecutionCommand command);

  LanceExecutionResult explainPlan(LanceExecutionCommand command);

  LanceExecutionResult analyzePlan(LanceExecutionCommand command);

  LanceExecutionResult create(LanceExecutionCommand command);
}
