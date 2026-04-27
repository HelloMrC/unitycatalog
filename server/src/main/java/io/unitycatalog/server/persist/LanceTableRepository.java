package io.unitycatalog.server.persist;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceNamespaceDAO;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.persist.dao.PropertyDAO;
import io.unitycatalog.server.persist.utils.TransactionManager;
import io.unitycatalog.server.utils.Constants;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;

public class LanceTableRepository {
  private static final String TABLE_ASSET_TYPE = "TABLE";
  private static final String ACTIVE_STATE = "ACTIVE";
  private static final String DECLARED_STATE = "DECLARED";
  private static final String DEREGISTERED_STATE = "DEREGISTERED";
  private static final String DROPPED_STATE = "DROPPED";

  private final SessionFactory sessionFactory;

  public LanceTableRepository(Repositories repositories, SessionFactory sessionFactory) {
    this.sessionFactory = sessionFactory;
  }

  public LanceAssetDAO registerTable(
      UUID namespaceId,
      String tableName,
      String pathKey,
      String canonicalIdentifier,
      String location,
      String arrowSchemaJson,
      String storageOptionsTemplateJson,
      Map<String, String> properties,
      String owner) {
    return createTable(
        namespaceId,
        tableName,
        pathKey,
        canonicalIdentifier,
        location,
        arrowSchemaJson,
        storageOptionsTemplateJson,
        false,
        properties,
        owner,
        ACTIVE_STATE);
  }

  public LanceAssetDAO declareTable(
      UUID namespaceId,
      String tableName,
      String pathKey,
      String canonicalIdentifier,
      String location,
      String arrowSchemaJson,
      String storageOptionsTemplateJson,
      Map<String, String> properties,
      String owner) {
    return createTable(
        namespaceId,
        tableName,
        pathKey,
        canonicalIdentifier,
        location,
        arrowSchemaJson,
        storageOptionsTemplateJson,
        true,
        properties,
        owner,
        DECLARED_STATE);
  }

  private LanceAssetDAO createTable(
      UUID namespaceId,
      String tableName,
      String pathKey,
      String canonicalIdentifier,
      String location,
      String arrowSchemaJson,
      String storageOptionsTemplateJson,
      boolean isOnlyDeclared,
      Map<String, String> properties,
      String owner,
      String assetState) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session ->
            createTable(
                session,
                namespaceId,
                tableName,
                pathKey,
                canonicalIdentifier,
                location,
                arrowSchemaJson,
                storageOptionsTemplateJson,
                isOnlyDeclared,
                properties,
                owner,
                assetState),
        "Failed to create Lance table metadata",
        false);
  }

  private LanceAssetDAO createTable(
      Session session,
      UUID namespaceId,
      String tableName,
      String pathKey,
      String canonicalIdentifier,
      String location,
      String arrowSchemaJson,
      String storageOptionsTemplateJson,
      boolean isOnlyDeclared,
      Map<String, String> properties,
      String owner,
      String assetState) {
    LanceNamespaceDAO namespaceDAO = session.get(LanceNamespaceDAO.class, namespaceId);
    if (namespaceDAO == null) {
      throw new BaseException(ErrorCode.NOT_FOUND, "Lance namespace not found: " + namespaceId);
    }
    if (findAsset(session, pathKey).isPresent()) {
      throw new BaseException(ErrorCode.ALREADY_EXISTS, "Lance table already exists: " + pathKey);
    }

    Date now = new Date();
    UUID assetId = UUID.randomUUID();
    LanceAssetDAO assetDAO =
        LanceAssetDAO.builder()
            .id(assetId)
            .name(tableName)
            .namespaceId(namespaceId)
            .assetType(TABLE_ASSET_TYPE)
            .pathKey(pathKey)
            .canonicalIdentifier(canonicalIdentifier)
            .state(assetState)
            .owner(owner)
            .createdAt(now)
            .createdBy(owner)
            .updatedAt(now)
            .updatedBy(owner)
            .build();
    LanceTableDAO tableDAO =
        LanceTableDAO.builder()
            .assetId(assetId)
            .storageLocation(location)
            .tableUri(location)
            .arrowSchemaJson(arrowSchemaJson)
            .storageOptionsTemplateJson(storageOptionsTemplateJson)
            .isOnlyDeclared(isOnlyDeclared)
            .managedVersioning(false)
            .build();

    session.persist(assetDAO);
    session.persist(tableDAO);
    PropertyDAO.from(properties, assetId, Constants.LANCE_TABLE).forEach(session::persist);
    return assetDAO;
  }

  public Optional<LanceAssetDAO> findAssetByPathKey(String pathKey) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> findActiveAsset(session, pathKey),
        "Failed to find Lance table metadata",
        true);
  }

  private Optional<LanceAssetDAO> findAsset(Session session, String pathKey) {
    // Used for duplicate check during creation - includes all states
    Query<LanceAssetDAO> query =
        session.createQuery("FROM LanceAssetDAO WHERE pathKey = :pathKey", LanceAssetDAO.class);
    query.setParameter("pathKey", pathKey);
    query.setMaxResults(1);
    return query.uniqueResultOptional();
  }

  private Optional<LanceAssetDAO> findActiveAsset(Session session, String pathKey) {
    // Only return assets that are ACTIVE or DECLARED, not DEREGISTERED or DROPPED
    String hql =
        "FROM LanceAssetDAO WHERE pathKey = :pathKey " + "AND state IN ('ACTIVE', 'DECLARED')";
    Query<LanceAssetDAO> query = session.createQuery(hql, LanceAssetDAO.class);
    query.setParameter("pathKey", pathKey);
    query.setMaxResults(1);
    return query.uniqueResultOptional();
  }

  public Optional<LanceTableDAO> findTableByAssetId(UUID assetId) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> Optional.ofNullable(session.get(LanceTableDAO.class, assetId)),
        "Failed to find Lance table details",
        true);
  }

  public List<LanceAssetDAO> listTables(UUID namespaceId, boolean includeDeclared) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> listTables(session, namespaceId, includeDeclared),
        "Failed to list Lance tables",
        true);
  }

  private List<LanceAssetDAO> listTables(
      Session session, UUID namespaceId, boolean includeDeclared) {
    String hql;
    if (includeDeclared) {
      hql =
          "SELECT a FROM LanceAssetDAO a WHERE a.namespaceId = :namespaceId "
              + "AND a.assetType = :assetType "
              + "AND a.state IN ('ACTIVE', 'DECLARED') ORDER BY a.name";
    } else {
      hql =
          "SELECT a FROM LanceAssetDAO a, LanceTableDAO t WHERE a.id = t.assetId "
              + "AND a.namespaceId = :namespaceId AND a.assetType = :assetType "
              + "AND a.state = 'ACTIVE' AND t.isOnlyDeclared = false ORDER BY a.name";
    }
    Query<LanceAssetDAO> query = session.createQuery(hql, LanceAssetDAO.class);
    query.setParameter("namespaceId", namespaceId);
    query.setParameter("assetType", TABLE_ASSET_TYPE);
    return query.list();
  }

  public Map<String, String> getTableProperties(UUID assetId) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session ->
            PropertyDAO.toMap(
                PropertyRepository.findProperties(session, assetId, Constants.LANCE_TABLE)),
        "Failed to load Lance table properties",
        true);
  }

  public List<LanceAssetDAO> listTables(
      UUID namespaceId,
      boolean includeDeclared,
      Optional<Integer> limit,
      Optional<String> pageToken) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> listTablesPaged(session, namespaceId, includeDeclared, limit, pageToken),
        "Failed to list Lance tables",
        true);
  }

  private List<LanceAssetDAO> listTablesPaged(
      Session session,
      UUID namespaceId,
      boolean includeDeclared,
      Optional<Integer> limit,
      Optional<String> pageToken) {
    String baseHql;
    if (includeDeclared) {
      baseHql =
          "SELECT a FROM LanceAssetDAO a WHERE a.namespaceId = :namespaceId "
              + "AND a.assetType = :assetType AND a.state IN ('ACTIVE', 'DECLARED')";
    } else {
      baseHql =
          "SELECT a FROM LanceAssetDAO a, LanceTableDAO t WHERE a.id = t.assetId "
              + "AND a.namespaceId = :namespaceId AND a.assetType = :assetType "
              + "AND a.state = 'ACTIVE' AND t.isOnlyDeclared = false";
    }

    String hql = baseHql;
    if (pageToken.isPresent()) {
      hql += " AND a.name > :pageToken";
    }
    hql += " ORDER BY a.name";

    Query<LanceAssetDAO> query = session.createQuery(hql, LanceAssetDAO.class);
    query.setParameter("namespaceId", namespaceId);
    query.setParameter("assetType", TABLE_ASSET_TYPE);
    pageToken.ifPresent(token -> query.setParameter("pageToken", token));
    limit.ifPresent(value -> query.setMaxResults(value + 1));
    return query.list();
  }

  public void dropDeclaredTable(UUID assetId) {
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          LanceAssetDAO assetDAO = session.get(LanceAssetDAO.class, assetId);
          if (assetDAO == null) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Lance table asset not found: " + assetId);
          }
          LanceTableDAO tableDAO = session.get(LanceTableDAO.class, assetId);
          if (tableDAO != null && !Boolean.TRUE.equals(tableDAO.getIsOnlyDeclared())) {
            throw new BaseException(
                ErrorCode.UNIMPLEMENTED,
                "Dropping registered tables not supported. Use deregister instead.");
          }

          // Delete properties first
          PropertyRepository.findProperties(session, assetId, Constants.LANCE_TABLE)
              .forEach(session::remove);

          // Delete table details
          if (tableDAO != null) {
            session.remove(tableDAO);
          }

          assetDAO.setState(DROPPED_STATE);
          assetDAO.setUpdatedAt(new Date());
          session.merge(assetDAO);
          return null;
        },
        "Failed to drop Lance declared table",
        false);
  }

  public void deregisterTable(UUID assetId) {
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          LanceAssetDAO assetDAO = session.get(LanceAssetDAO.class, assetId);
          if (assetDAO == null) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Lance table asset not found: " + assetId);
          }

          // Delete properties first
          PropertyRepository.findProperties(session, assetId, Constants.LANCE_TABLE)
              .forEach(session::remove);

          // Delete table details
          LanceTableDAO tableDAO = session.get(LanceTableDAO.class, assetId);
          if (tableDAO != null) {
            session.remove(tableDAO);
          }

          assetDAO.setState(DEREGISTERED_STATE);
          assetDAO.setUpdatedAt(new Date());
          session.merge(assetDAO);

          return null;
        },
        "Failed to deregister Lance table",
        false);
  }
}
