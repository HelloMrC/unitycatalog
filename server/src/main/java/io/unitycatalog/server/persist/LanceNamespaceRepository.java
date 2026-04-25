package io.unitycatalog.server.persist;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.dao.LanceNamespaceDAO;
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

public class LanceNamespaceRepository {
  private final SessionFactory sessionFactory;

  public LanceNamespaceRepository(Repositories repositories, SessionFactory sessionFactory) {
    this.sessionFactory = sessionFactory;
  }

  public LanceNamespaceDAO createNamespace(
      UUID rootScopeId,
      UUID parentNamespaceId,
      String name,
      int depth,
      String pathKey,
      String displayName,
      String owner,
      Map<String, String> properties) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> createNamespace(session, rootScopeId, parentNamespaceId, name, depth, pathKey,
            displayName, owner, properties),
        "Failed to create Lance namespace",
        false);
  }

  public LanceNamespaceDAO createNamespace(
      Session session,
      UUID rootScopeId,
      UUID parentNamespaceId,
      String name,
      int depth,
      String pathKey,
      String displayName,
      String owner,
      Map<String, String> properties) {
    findNamespace(session, rootScopeId, pathKey)
        .ifPresent(
            existing -> {
              throw new BaseException(
                  ErrorCode.ALREADY_EXISTS, "Lance namespace already exists: " + pathKey);
            });
    if (parentNamespaceId != null && session.get(LanceNamespaceDAO.class, parentNamespaceId) == null) {
      throw new BaseException(
          ErrorCode.NOT_FOUND, "Parent Lance namespace not found: " + parentNamespaceId);
    }

    Date now = new Date();
    LanceNamespaceDAO namespaceDAO =
        LanceNamespaceDAO.builder()
            .id(UUID.randomUUID())
            .name(name)
            .rootScopeId(rootScopeId)
            .parentNamespaceId(parentNamespaceId)
            .depth(depth)
            .pathKey(pathKey)
            .displayName(displayName)
            .owner(owner)
            .createdAt(now)
            .createdBy(owner)
            .updatedAt(now)
            .updatedBy(owner)
            .build();

    session.persist(namespaceDAO);
    PropertyDAO.from(properties, namespaceDAO.getId(), Constants.LANCE_NAMESPACE)
        .forEach(session::persist);
    return namespaceDAO;
  }

  public Optional<LanceNamespaceDAO> findNamespace(UUID rootScopeId, String pathKey) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> findNamespace(session, rootScopeId, pathKey),
        "Failed to find Lance namespace",
        true);
  }

  public Optional<LanceNamespaceDAO> findNamespace(
      Session session, UUID rootScopeId, String pathKey) {
    Query<LanceNamespaceDAO> query =
        session.createQuery(
            "FROM LanceNamespaceDAO WHERE rootScopeId = :rootScopeId AND pathKey = :pathKey",
            LanceNamespaceDAO.class);
    query.setParameter("rootScopeId", rootScopeId);
    query.setParameter("pathKey", pathKey);
    query.setMaxResults(1);
    return query.uniqueResultOptional();
  }

  public LanceNamespaceDAO getNamespaceOrThrow(UUID rootScopeId, String pathKey) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session ->
            findNamespace(session, rootScopeId, pathKey)
                .orElseThrow(
                    () ->
                        new BaseException(
                            ErrorCode.NOT_FOUND, "Lance namespace not found: " + pathKey)),
        "Failed to get Lance namespace",
        true);
  }

  public List<LanceNamespaceDAO> listChildNamespaces(UUID rootScopeId, UUID parentNamespaceId) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> listChildNamespaces(session, rootScopeId, parentNamespaceId),
        "Failed to list Lance namespaces",
        true);
  }

  public List<LanceNamespaceDAO> listChildNamespaces(
      Session session, UUID rootScopeId, UUID parentNamespaceId) {
    String hql =
        parentNamespaceId == null
            ? "FROM LanceNamespaceDAO WHERE rootScopeId = :rootScopeId "
                + "AND parentNamespaceId IS NULL ORDER BY name"
            : "FROM LanceNamespaceDAO WHERE rootScopeId = :rootScopeId "
                + "AND parentNamespaceId = :parentNamespaceId ORDER BY name";
    Query<LanceNamespaceDAO> query = session.createQuery(hql, LanceNamespaceDAO.class);
    query.setParameter("rootScopeId", rootScopeId);
    if (parentNamespaceId != null) {
      query.setParameter("parentNamespaceId", parentNamespaceId);
    }
    return query.list();
  }

  public Map<String, String> getNamespaceProperties(UUID namespaceId) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> PropertyDAO.toMap(PropertyRepository.findProperties(
            session, namespaceId, Constants.LANCE_NAMESPACE)),
        "Failed to load Lance namespace properties",
        true);
  }
}
