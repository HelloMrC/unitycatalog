package io.unitycatalog.server.persist;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.dao.LanceTransactionDAO;
import io.unitycatalog.server.persist.utils.TransactionManager;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;

public class LanceTransactionRepository {
  private final SessionFactory sessionFactory;

  public LanceTransactionRepository(Repositories repositories, SessionFactory sessionFactory) {
    this.sessionFactory = sessionFactory;
  }

  public LanceTransactionDAO upsertTransaction(
      UUID tableAssetId,
      String transactionKey,
      String status,
      String actionsJson,
      String commitMetadataJson,
      String createdBy) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session ->
            upsertTransaction(
                session,
                tableAssetId,
                transactionKey,
                status,
                actionsJson,
                commitMetadataJson,
                createdBy),
        "Failed to upsert Lance transaction metadata",
        false);
  }

  private LanceTransactionDAO upsertTransaction(
      Session session,
      UUID tableAssetId,
      String transactionKey,
      String status,
      String actionsJson,
      String commitMetadataJson,
      String createdBy) {
    validateTransaction(transactionKey, status);

    Optional<LanceTransactionDAO> existing = findByKey(session, transactionKey);
    if (existing.isPresent()) {
      LanceTransactionDAO dao = existing.get();
      dao.setStatus(status);
      dao.setActionsJson(actionsJson);
      dao.setCommitMetadataJson(commitMetadataJson);
      dao.setUpdatedAt(new Date());
      dao.setUpdatedBy(createdBy);
      session.merge(dao);
      return dao;
    }

    Date now = new Date();
    UUID transactionId = UUID.randomUUID();
    LanceTransactionDAO dao =
        LanceTransactionDAO.builder()
            .id(transactionId)
            .assetId(transactionId)
            .transactionKey(transactionKey)
            .tableAssetId(tableAssetId)
            .status(status)
            .actionsJson(actionsJson)
            .commitMetadataJson(commitMetadataJson)
            .createdAt(now)
            .createdBy(createdBy)
            .updatedAt(now)
            .updatedBy(createdBy)
            .build();
    session.persist(dao);
    return dao;
  }

  public Optional<LanceTransactionDAO> findByKey(String transactionKey) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> findByKey(session, transactionKey),
        "Failed to find Lance transaction metadata",
        true);
  }

  private Optional<LanceTransactionDAO> findByKey(Session session, String transactionKey) {
    Query<LanceTransactionDAO> query =
        session.createQuery(
            "FROM LanceTransactionDAO WHERE transactionKey = :transactionKey",
            LanceTransactionDAO.class);
    query.setParameter("transactionKey", transactionKey);
    query.setMaxResults(1);
    return query.uniqueResultOptional();
  }

  public List<LanceTransactionDAO> listTransactions(
      UUID tableAssetId, Optional<String> statusFilter, Optional<Integer> limit) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> listTransactions(session, tableAssetId, statusFilter, limit),
        "Failed to list Lance transaction metadata",
        true);
  }

  private List<LanceTransactionDAO> listTransactions(
      Session session, UUID tableAssetId, Optional<String> statusFilter, Optional<Integer> limit) {
    String hql = "FROM LanceTransactionDAO WHERE tableAssetId = :tableAssetId";
    if (statusFilter.isPresent()) {
      hql += " AND status = :status";
    }
    hql += " ORDER BY createdAt DESC";

    Query<LanceTransactionDAO> query = session.createQuery(hql, LanceTransactionDAO.class);
    query.setParameter("tableAssetId", tableAssetId);
    statusFilter.ifPresent(status -> query.setParameter("status", status));
    limit.filter(value -> value > 0).ifPresent(query::setMaxResults);
    return query.list();
  }

  private void validateTransaction(String transactionKey, String status) {
    if (transactionKey == null || transactionKey.isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance transaction_key is required.");
    }
    if (status == null || status.isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance transaction status is required.");
    }
    validateStatus(status);
  }

  private void validateStatus(String status) {
    List<String> validStatuses = List.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED");
    if (!validStatuses.contains(status)) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          "Invalid Lance transaction status: " + status + ". Valid statuses: " + validStatuses);
    }
  }
}
