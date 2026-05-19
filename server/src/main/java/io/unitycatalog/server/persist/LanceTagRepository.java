package io.unitycatalog.server.persist;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.dao.LanceTagDAO;
import io.unitycatalog.server.persist.utils.TransactionManager;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;

public class LanceTagRepository {
  private final SessionFactory sessionFactory;

  public LanceTagRepository(Repositories repositories, SessionFactory sessionFactory) {
    this.sessionFactory = sessionFactory;
  }

  public LanceTagDAO createTag(
      UUID tableAssetId, String tagName, Long version, String metadataJson, String createdBy) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> createTag(session, tableAssetId, tagName, version, metadataJson, createdBy),
        "Failed to create Lance tag metadata",
        false);
  }

  private LanceTagDAO createTag(
      Session session,
      UUID tableAssetId,
      String tagName,
      Long version,
      String metadataJson,
      String createdBy) {
    validateTag(tagName, version);
    if (findTag(session, tableAssetId, tagName).isPresent()) {
      throw new BaseException(ErrorCode.ALREADY_EXISTS, "Lance tag already exists: " + tagName);
    }

    Date now = new Date();
    UUID tagId = UUID.randomUUID();
    LanceTagDAO tagDAO =
        LanceTagDAO.builder()
            .id(tagId)
            .assetId(tagId)
            .tableAssetId(tableAssetId)
            .tagName(tagName)
            .version(version)
            .metadataJson(metadataJson)
            .createdAt(now)
            .createdBy(createdBy)
            .updatedAt(now)
            .updatedBy(createdBy)
            .build();
    session.persist(tagDAO);
    return tagDAO;
  }

  public LanceTagDAO updateTag(
      UUID tableAssetId, String tagName, Long version, String metadataJson, String updatedBy) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> updateTag(session, tableAssetId, tagName, version, metadataJson, updatedBy),
        "Failed to update Lance tag metadata",
        false);
  }

  private LanceTagDAO updateTag(
      Session session,
      UUID tableAssetId,
      String tagName,
      Long version,
      String metadataJson,
      String updatedBy) {
    validateTag(tagName, version);
    LanceTagDAO tagDAO =
        findTag(session, tableAssetId, tagName)
            .orElseThrow(
                () -> new BaseException(ErrorCode.NOT_FOUND, "Lance tag not found: " + tagName));
    tagDAO.setVersion(version);
    tagDAO.setMetadataJson(metadataJson);
    tagDAO.setUpdatedAt(new Date());
    tagDAO.setUpdatedBy(updatedBy);
    session.merge(tagDAO);
    return tagDAO;
  }

  public void deleteTag(UUID tableAssetId, String tagName) {
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          LanceTagDAO tagDAO =
              findTag(session, tableAssetId, tagName)
                  .orElseThrow(
                      () ->
                          new BaseException(
                              ErrorCode.NOT_FOUND, "Lance tag not found: " + tagName));
          session.remove(tagDAO);
          return null;
        },
        "Failed to delete Lance tag metadata",
        false);
  }

  public Optional<LanceTagDAO> findTag(UUID tableAssetId, String tagName) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> findTag(session, tableAssetId, tagName),
        "Failed to find Lance tag metadata",
        true);
  }

  private Optional<LanceTagDAO> findTag(Session session, UUID tableAssetId, String tagName) {
    Query<LanceTagDAO> query =
        session.createQuery(
            "FROM LanceTagDAO WHERE tableAssetId = :tableAssetId AND tagName = :tagName",
            LanceTagDAO.class);
    query.setParameter("tableAssetId", tableAssetId);
    query.setParameter("tagName", tagName);
    query.setMaxResults(1);
    return query.uniqueResultOptional();
  }

  public List<LanceTagDAO> listTags(
      UUID tableAssetId, Optional<Integer> limit, Optional<String> pageToken) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> listTags(session, tableAssetId, limit, pageToken),
        "Failed to list Lance tag metadata",
        true);
  }

  private List<LanceTagDAO> listTags(
      Session session, UUID tableAssetId, Optional<Integer> limit, Optional<String> pageToken) {
    String hql = "FROM LanceTagDAO WHERE tableAssetId = :tableAssetId";
    if (pageToken.isPresent()) {
      hql += " AND tagName > :pageToken";
    }
    hql += " ORDER BY tagName";

    Query<LanceTagDAO> query = session.createQuery(hql, LanceTagDAO.class);
    query.setParameter("tableAssetId", tableAssetId);
    pageToken.ifPresent(value -> query.setParameter("pageToken", value));
    limit.filter(value -> value > 0).ifPresent(value -> query.setMaxResults(value + 1));
    return query.list();
  }

  public LanceTagDAO upsertTag(
      UUID tableAssetId, String tagName, Long version, String metadataJson, String createdBy) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> upsertTag(session, tableAssetId, tagName, version, metadataJson, createdBy),
        "Failed to upsert Lance tag metadata",
        false);
  }

  private LanceTagDAO upsertTag(
      Session session,
      UUID tableAssetId,
      String tagName,
      Long version,
      String metadataJson,
      String createdBy) {
    validateTag(tagName, version);
    Optional<LanceTagDAO> existing = findTag(session, tableAssetId, tagName);
    if (existing.isPresent()) {
      LanceTagDAO tagDAO = existing.get();
      tagDAO.setVersion(version);
      tagDAO.setMetadataJson(metadataJson);
      tagDAO.setUpdatedAt(new Date());
      tagDAO.setUpdatedBy(createdBy);
      session.merge(tagDAO);
      return tagDAO;
    }

    Date now = new Date();
    UUID tagId = UUID.randomUUID();
    LanceTagDAO tagDAO =
        LanceTagDAO.builder()
            .id(tagId)
            .assetId(tagId)
            .tableAssetId(tableAssetId)
            .tagName(tagName)
            .version(version)
            .metadataJson(metadataJson)
            .createdAt(now)
            .createdBy(createdBy)
            .updatedAt(now)
            .updatedBy(createdBy)
            .build();
    session.persist(tagDAO);
    return tagDAO;
  }

  private void validateTag(String tagName, Long version) {
    if (tagName == null || tagName.isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance tag_name is required.");
    }
    if (version == null) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance tag version is required.");
    }
  }
}
