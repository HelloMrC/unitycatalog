package io.unitycatalog.server.service.lance.backend;

/**
 * Extended Lance execution backend interface for Phase 3 advanced operations.
 *
 * <p>Phase 3 operations require Lance SDK/Worker execution and metadata sync back to UC. These
 * operations cannot be performed locally by UC because they involve physical Lance file format
 * operations.
 *
 * <p>This interface extends the base LanceExecutionBackend to maintain backward compatibility with
 * Phase 2 implementations.
 */
public interface LanceAdvancedExecutionBackend extends LanceExecutionBackend {

  // ========== Index Operations ==========

  /**
   * Creates an index on the Lance table.
   *
   * <p>After successful execution, the executor should call syncIndex API to record the index
   * metadata in UC.
   */
  LanceExecutionResult createIndex(LanceExecutionCommand command);

  /**
   * Drops an index from the Lance table.
   *
   * <p>After successful execution, UC metadata should be updated to reflect the dropped index.
   */
  LanceExecutionResult dropIndex(LanceExecutionCommand command);

  // ========== Version Operations ==========

  /**
   * Deletes specified versions from the Lance table.
   *
   * <p>This operation requires physical Lance manifest manipulation and cannot be performed locally
   * by UC. After successful execution, UC metadata should be updated to reflect the deleted
   * versions.
   */
  LanceExecutionResult deleteVersions(LanceExecutionCommand command);

  // ========== Transaction Operations ==========

  /**
   * Executes a batch commit operation.
   *
   * <p>After successful execution, the executor should call syncTransaction API to record the
   * transaction state in UC.
   */
  LanceExecutionResult batchCommit(LanceExecutionCommand command);

  /**
   * Alters an existing transaction.
   *
   * <p>After successful execution, the executor should call syncTransaction API to update the
   * transaction state in UC.
   */
  LanceExecutionResult alterTransaction(LanceExecutionCommand command);

  // ========== Schema Evolution Operations ==========

  /**
   * Adds columns to the Lance table schema.
   *
   * <p>After successful execution, the executor should call syncSchema API to update the schema
   * metadata in UC.
   */
  LanceExecutionResult addColumns(LanceExecutionCommand command);

  /**
   * Alters existing columns in the Lance table schema.
   *
   * <p>After successful execution, the executor should call syncSchema API to update the schema
   * metadata in UC.
   */
  LanceExecutionResult alterColumns(LanceExecutionCommand command);

  /**
   * Drops columns from the Lance table schema.
   *
   * <p>After successful execution, the executor should call syncSchema API to update the schema
   * metadata in UC.
   */
  LanceExecutionResult dropColumns(LanceExecutionCommand command);

  // ========== Restore Operations ==========

  /**
   * Restores the Lance table to a previous version.
   *
   * <p>This operation requires physical Lance file format manipulation and cannot be performed
   * locally by UC.
   */
  LanceExecutionResult restoreTable(LanceExecutionCommand command);
}
