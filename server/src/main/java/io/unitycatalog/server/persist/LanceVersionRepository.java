package io.unitycatalog.server.persist;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.dao.LanceVersionDAO;
import io.unitycatalog.server.persist.utils.TransactionManager;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;

public class LanceVersionRepository {
  private final SessionFactory sessionFactory;

  public LanceVersionRepository(Repositories repositories, SessionFactory sessionFactory) {
    this.sessionFactory = sessionFactory;
  }

  public LanceVersionDAO upsertVersion(
      UUID assetId,
      Long version,
      String operation,
      Date timestamp,
      String manifestPath,
      Long manifestSize,
      String etag,
      String metadataJson,
      String statsJson,
      String createdBy) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session ->
            upsertVersion(
                session,
                assetId,
                version,
                operation,
                timestamp,
                manifestPath,
                manifestSize,
                etag,
                metadataJson,
                statsJson,
                createdBy),
        "Failed to upsert Lance version metadata",
        false);
  }

  private LanceVersionDAO upsertVersion(
      Session session,
      UUID assetId,
      Long version,
      String operation,
      Date timestamp,
      String manifestPath,
      Long manifestSize,
      String etag,
      String metadataJson,
      String statsJson,
      String createdBy) {
    if (version == null) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance version is required.");
    }
    if (operation == null || operation.isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance version operation is required.");
    }

    Date resolvedTimestamp = timestamp == null ? new Date() : timestamp;
    Optional<LanceVersionDAO> existing = findVersion(session, assetId, version);
    if (existing.isPresent()) {
      LanceVersionDAO versionDAO = existing.get();
      versionDAO.setOperation(operation);
      versionDAO.setTimestamp(resolvedTimestamp);
      versionDAO.setManifestPath(manifestPath);
      versionDAO.setManifestSize(manifestSize);
      versionDAO.setEtag(etag);
      versionDAO.setMetadataJson(metadataJson);
      versionDAO.setStatsJson(statsJson);
      versionDAO.setCreatedBy(createdBy);
      session.merge(versionDAO);
      return versionDAO;
    }

    LanceVersionDAO versionDAO =
        LanceVersionDAO.builder()
            .id(UUID.randomUUID())
            .assetId(assetId)
            .version(version)
            .operation(operation)
            .timestamp(resolvedTimestamp)
            .manifestPath(manifestPath)
            .manifestSize(manifestSize)
            .etag(etag)
            .metadataJson(metadataJson)
            .statsJson(statsJson)
            .createdBy(createdBy)
            .createdAt(new Date())
            .build();
    session.persist(versionDAO);
    return versionDAO;
  }

  public Optional<LanceVersionDAO> findVersion(UUID assetId, Long version) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> findVersion(session, assetId, version),
        "Failed to find Lance version metadata",
        true);
  }

  private Optional<LanceVersionDAO> findVersion(Session session, UUID assetId, Long version) {
    Query<LanceVersionDAO> query =
        session.createQuery(
            "FROM LanceVersionDAO WHERE assetId = :assetId AND version = :version",
            LanceVersionDAO.class);
    query.setParameter("assetId", assetId);
    query.setParameter("version", version);
    query.setMaxResults(1);
    return query.uniqueResultOptional();
  }

  public boolean versionExists(UUID assetId, Long version) {
    return findVersion(assetId, version).isPresent();
  }

  public List<LanceVersionDAO> listVersions(
      UUID assetId,
      Optional<Long> startVersion,
      Optional<Long> endVersion,
      Optional<Integer> limit,
      Optional<Long> pageToken) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> listVersions(session, assetId, startVersion, endVersion, limit, pageToken),
        "Failed to list Lance version metadata",
        true);
  }

  private List<LanceVersionDAO> listVersions(
      Session session,
      UUID assetId,
      Optional<Long> startVersion,
      Optional<Long> endVersion,
      Optional<Integer> limit,
      Optional<Long> pageToken) {
    String hql = "FROM LanceVersionDAO WHERE assetId = :assetId";
    if (startVersion.isPresent()) {
      hql += " AND version >= :startVersion";
    }
    if (endVersion.isPresent()) {
      hql += " AND version <= :endVersion";
    }
    if (pageToken.isPresent()) {
      hql += " AND version < :pageToken";
    }
    hql += " ORDER BY version DESC";

    Query<LanceVersionDAO> query = session.createQuery(hql, LanceVersionDAO.class);
    query.setParameter("assetId", assetId);
    startVersion.ifPresent(value -> query.setParameter("startVersion", value));
    endVersion.ifPresent(value -> query.setParameter("endVersion", value));
    pageToken.ifPresent(value -> query.setParameter("pageToken", value));
    limit.filter(value -> value > 0).ifPresent(value -> query.setMaxResults(value + 1));
    return query.list();
  }
}
