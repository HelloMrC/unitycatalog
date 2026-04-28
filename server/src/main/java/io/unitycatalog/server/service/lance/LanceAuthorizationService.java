package io.unitycatalog.server.service.lance;

import io.unitycatalog.server.auth.UnityCatalogAuthorizer;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.MetastoreRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.UserRepository;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceNamespaceDAO;
import io.unitycatalog.server.persist.model.Privileges;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class LanceAuthorizationService {
  private static final String ADMIN_USER = "admin";

  private final UnityCatalogAuthorizer authorizer;
  private final UserRepository userRepository;
  private final MetastoreRepository metastoreRepository;

  LanceAuthorizationService(Repositories repositories, UnityCatalogAuthorizer authorizer) {
    this.authorizer = authorizer;
    this.userRepository = repositories.getUserRepository();
    this.metastoreRepository = repositories.getMetastoreRepository();
  }

  void authorizeCreateNamespace(LanceNamespaceDAO parentNamespace) {
    String principal = currentPrincipal();
    if (principal == null) {
      return;
    }
    if (parentNamespace == null) {
      if (ADMIN_USER.equals(principal)
          || authorizeAny(
              metastoreRepository.getMetastoreId(),
              Privileges.OWNER,
              Privileges.CREATE_NAMESPACE)) {
        return;
      }
      throw permissionDenied("CREATE_NAMESPACE on metastore requires admin privileges.");
    }

    if (principal.equals(parentNamespace.getOwner())
        || authorizeAny(parentNamespace.getId(), Privileges.OWNER)
        || authorizeAll(
            parentNamespace.getId(), Privileges.USE_NAMESPACE, Privileges.CREATE_NAMESPACE)) {
      return;
    }
    throw permissionDenied("USE_NAMESPACE and CREATE_NAMESPACE required on parent namespace.");
  }

  void authorizeReadNamespace(LanceNamespaceDAO namespaceDAO) {
    String principal = currentPrincipal();
    if (principal == null
        || namespaceDAO.getOwner() == null
        || principal.equals(namespaceDAO.getOwner())
        || authorizeAny(namespaceDAO.getId(), Privileges.OWNER, Privileges.READ_METADATA)) {
      return;
    }
    throw permissionDenied("READ_METADATA required on Lance namespace.");
  }

  void authorizeModifyNamespace(LanceNamespaceDAO namespaceDAO) {
    String principal = currentPrincipal();
    if (principal == null
        || namespaceDAO.getOwner() == null
        || principal.equals(namespaceDAO.getOwner())
        || authorizeAny(namespaceDAO.getId(), Privileges.OWNER, Privileges.MODIFY)) {
      return;
    }
    throw permissionDenied("MODIFY required on Lance namespace.");
  }

  void authorizeReadTable(LanceAssetDAO assetDAO) {
    String principal = currentPrincipal();
    if (principal == null
        || assetDAO.getOwner() == null
        || principal.equals(assetDAO.getOwner())
        || authorizeAny(assetDAO.getId(), Privileges.OWNER, Privileges.READ_METADATA)) {
      return;
    }
    throw permissionDenied("READ_METADATA required on Lance table.");
  }

  void authorizeModifyTable(LanceAssetDAO assetDAO) {
    String principal = currentPrincipal();
    if (principal == null
        || assetDAO.getOwner() == null
        || principal.equals(assetDAO.getOwner())
        || authorizeAny(assetDAO.getId(), Privileges.OWNER, Privileges.MODIFY)) {
      return;
    }
    throw permissionDenied("MODIFY required on Lance table.");
  }

  void initializeNamespaceAuthorization(
      LanceNamespaceDAO namespaceDAO, LanceNamespaceDAO parentNamespace) {
    currentPrincipalId()
        .ifPresent(
            principalId ->
                authorizer.grantAuthorization(principalId, namespaceDAO.getId(), Privileges.OWNER));
    UUID parentId =
        parentNamespace == null ? metastoreRepository.getMetastoreId() : parentNamespace.getId();
    authorizer.addHierarchyChild(parentId, namespaceDAO.getId());
  }

  void initializeTableAuthorization(LanceAssetDAO assetDAO, LanceNamespaceDAO namespaceDAO) {
    currentPrincipalId()
        .ifPresent(
            principalId ->
                authorizer.grantAuthorization(principalId, assetDAO.getId(), Privileges.OWNER));
    authorizer.addHierarchyChild(namespaceDAO.getId(), assetDAO.getId());
  }

  void removeNamespaceAuthorization(LanceNamespaceDAO namespaceDAO) {
    authorizer.clearAuthorizationsForResource(namespaceDAO.getId());
    UUID parentId =
        namespaceDAO.getParentNamespaceId() == null
            ? metastoreRepository.getMetastoreId()
            : namespaceDAO.getParentNamespaceId();
    authorizer.removeHierarchyChild(parentId, namespaceDAO.getId());
    authorizer.removeHierarchyChildren(namespaceDAO.getId());
  }

  void removeTableAuthorization(LanceAssetDAO assetDAO) {
    authorizer.clearAuthorizationsForResource(assetDAO.getId());
    authorizer.removeHierarchyChild(assetDAO.getNamespaceId(), assetDAO.getId());
  }

  Map<String, Object> authorizationMetadata() {
    if (LanceRequestContext.currentPrincipal() == null) {
      return null;
    }
    return Map.of(
        "mapper",
        "LanceResourceKeyMapper",
        "parent_graph_checked",
        true,
        "expanded_parent_map",
        false);
  }

  private BaseException permissionDenied(String message) {
    return new BaseException(ErrorCode.PERMISSION_DENIED, message);
  }

  private boolean authorizeAny(UUID resourceId, Privileges... privileges) {
    return currentPrincipalId()
        .map(principalId -> authorizer.authorizeAny(principalId, resourceId, privileges))
        .orElse(false);
  }

  private boolean authorizeAll(UUID resourceId, Privileges... privileges) {
    return currentPrincipalId()
        .map(principalId -> authorizer.authorizeAll(principalId, resourceId, privileges))
        .orElse(false);
  }

  private Optional<UUID> currentPrincipalId() {
    String principal = currentPrincipal();
    if (principal == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(userRepository.getUserByEmail(principal).getId()));
    } catch (BaseException e) {
      if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private String currentPrincipal() {
    return LanceRequestContext.currentPrincipal();
  }
}
