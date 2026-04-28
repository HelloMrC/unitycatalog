package io.unitycatalog.server.service.lance.backend;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;

public class DisabledLanceExecutionBackend implements LanceExecutionBackend {
  private static final String MESSAGE =
      "Lance data plane execution backend is not configured for this server.";

  @Override
  public LanceExecutionResult query(LanceExecutionCommand command) {
    throw unimplemented();
  }

  @Override
  public LanceExecutionResult countRows(LanceExecutionCommand command) {
    throw unimplemented();
  }

  @Override
  public LanceExecutionResult stats(LanceExecutionCommand command) {
    throw unimplemented();
  }

  @Override
  public LanceExecutionResult insert(LanceExecutionCommand command) {
    throw unimplemented();
  }

  @Override
  public LanceExecutionResult mergeInsert(LanceExecutionCommand command) {
    throw unimplemented();
  }

  @Override
  public LanceExecutionResult update(LanceExecutionCommand command) {
    throw unimplemented();
  }

  @Override
  public LanceExecutionResult delete(LanceExecutionCommand command) {
    throw unimplemented();
  }

  @Override
  public LanceExecutionResult explainPlan(LanceExecutionCommand command) {
    throw unimplemented();
  }

  @Override
  public LanceExecutionResult analyzePlan(LanceExecutionCommand command) {
    throw unimplemented();
  }

  @Override
  public LanceExecutionResult create(LanceExecutionCommand command) {
    throw unimplemented();
  }

  private BaseException unimplemented() {
    return new BaseException(ErrorCode.UNIMPLEMENTED, MESSAGE);
  }
}
