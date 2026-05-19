package io.unitycatalog.server.persist;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.dao.LanceIndexDAO;
import io.unitycatalog.server.persist.utils.TransactionManager;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;

public class LanceIndexRepository {
  private final SessionFactory sessionFactory;

  public LanceIndexRepository(Repositories repositories, SessionFactory sessionFactory) {
    this.sessionFactory = sessionFactory;
  }

  public LanceIndexDAO upsertIndex(
      UUID tableAssetId,
      String indexName,
      String indexType,
      String targetColumnsJson,
      String distanceType,
      String buildParamsJson,
      String statsJson,
      String status,
      String createdBy) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session ->
            upsertIndex(
                session,
                tableAssetId,
                indexName,
                indexType,
                targetColumnsJson,
                distanceType,
                buildParamsJson,
                statsJson,
                status,
                createdBy),
        "Failed to upsert Lance index metadata",
        false);
  }

  private LanceIndexDAO upsertIndex(
      Session session,
      UUID tableAssetId,
      String indexName,
      String indexType,
      String targetColumnsJson,
      String distanceType,
      String buildParamsJson,
      String statsJson,
      String status,
      String createdBy) {
    if (indexName == null || indexName.isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance index name is required.");
    }
    if (indexType == null || indexType.isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance index type is required.");
    }
    if (targetColumnsJson == null || targetColumnsJson.isBlank()) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT, "Lance index target columns are required.");
    }
    if (status == null || status.isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance index status is required.");
    }

    Optional<LanceIndexDAO> existing = findIndex(session, tableAssetId, indexName);
    if (existing.isPresent()) {
      LanceIndexDAO indexDAO = existing.get();
      indexDAO.setIndexType(indexType);
      indexDAO.setTargetColumnsJson(targetColumnsJson);
      indexDAO.setDistanceType(distanceType);
      indexDAO.setBuildParamsJson(buildParamsJson);
      indexDAO.setStatsJson(statsJson);
      indexDAO.setStatus(status);
      indexDAO.setUpdatedAt(new Date());
      indexDAO.setUpdatedBy(createdBy);
      session.merge(indexDAO);
      return indexDAO;
    }

    LanceIndexDAO indexDAO =
        LanceIndexDAO.builder()
            .id(UUID.randomUUID())
            .assetId(UUID.randomUUID())
            .tableAssetId(tableAssetId)
            .indexName(indexName)
            .indexType(indexType)
            .targetColumnsJson(targetColumnsJson)
            .distanceType(distanceType)
            .buildParamsJson(buildParamsJson)
            .statsJson(statsJson)
            .status(status)
            .createdAt(new Date())
            .createdBy(createdBy)
            .build();
    session.persist(indexDAO);
    return indexDAO;
  }

  public Optional<LanceIndexDAO> findIndex(UUID tableAssetId, String indexName) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> findIndex(session, tableAssetId, indexName),
        "Failed to find Lance index metadata",
        true);
  }

  private Optional<LanceIndexDAO> findIndex(Session session, UUID tableAssetId, String indexName) {
    Query<LanceIndexDAO> query =
        session.createQuery(
            "FROM LanceIndexDAO WHERE tableAssetId = :tableAssetId AND indexName = :indexName",
            LanceIndexDAO.class);
    query.setParameter("tableAssetId", tableAssetId);
    query.setParameter("indexName", indexName);
    query.setMaxResults(1);
    return query.uniqueResultOptional();
  }

  public boolean indexExists(UUID tableAssetId, String indexName) {
    return findIndex(tableAssetId, indexName).isPresent();
  }

  public List<LanceIndexDAO> listIndices(
      UUID tableAssetId, Optional<String> statusFilter, Optional<Integer> limit) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> listIndices(session, tableAssetId, statusFilter, limit),
        "Failed to list Lance index metadata",
        true);
  }

  private List<LanceIndexDAO> listIndices(
      Session session, UUID tableAssetId, Optional<String> statusFilter, Optional<Integer> limit) {
    String hql = "FROM LanceIndexDAO WHERE tableAssetId = :tableAssetId";
    if (statusFilter.isPresent()) {
      hql += " AND status = :status";
    }
    hql += " ORDER BY indexName ASC";

    Query<LanceIndexDAO> query = session.createQuery(hql, LanceIndexDAO.class);
    query.setParameter("tableAssetId", tableAssetId);
    statusFilter.ifPresent(status -> query.setParameter("status", status));
    limit.filter(value -> value > 0).ifPresent(query::setMaxResults);
    return query.list();
  }

  public void deleteIndex(UUID tableAssetId, String indexName) {
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          findIndex(session, tableAssetId, indexName)
              .ifPresentOrElse(
                  indexDAO -> session.remove(indexDAO),
                  () -> {
                    throw new BaseException(
                        ErrorCode.NOT_FOUND, "Lance index not found: " + indexName);
                  });
          return null;
        },
        "Failed to delete Lance index metadata",
        false);
  }
}
