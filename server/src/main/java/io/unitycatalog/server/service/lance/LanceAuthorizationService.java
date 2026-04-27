package io.unitycatalog.server.service.lance;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceNamespaceDAO;
import java.util.Map;

class LanceAuthorizationService {
  private static final String ADMIN_USER = "admin";

  void authorizeCreateNamespace(LanceNamespaceDAO parentNamespace) {
    String principal = LanceRequestContext.currentPrincipal();
    if (principal == null) {
      return;
    }
    // Root namespace creation requires admin privileges
    if (parentNamespace == null) {
      if (!ADMIN_USER.equals(principal)) {
        throw permissionDenied("CREATE_NAMESPACE on metastore requires admin privileges.");
      }
      return;
    }
    // Child namespace creation requires parent owner privileges
    if (parentNamespace.getOwner() == null) {
      return;
    }
    if (!principal.equals(parentNamespace.getOwner())) {
      throw permissionDenied("USE_NAMESPACE and CREATE_NAMESPACE required on parent namespace.");
    }
  }

  void authorizeReadNamespace(LanceNamespaceDAO namespaceDAO) {
    String principal = LanceRequestContext.currentPrincipal();
    if (principal == null
        || namespaceDAO.getOwner() == null
        || principal.equals(namespaceDAO.getOwner())) {
      return;
    }
    throw permissionDenied("READ_METADATA required on Lance namespace.");
  }

  void authorizeModifyNamespace(LanceNamespaceDAO namespaceDAO) {
    String principal = LanceRequestContext.currentPrincipal();
    if (principal == null
        || namespaceDAO.getOwner() == null
        || principal.equals(namespaceDAO.getOwner())) {
      return;
    }
    throw permissionDenied("MODIFY required on Lance namespace.");
  }

  void authorizeReadTable(LanceAssetDAO assetDAO) {
    String principal = LanceRequestContext.currentPrincipal();
    if (principal == null || assetDAO.getOwner() == null || principal.equals(assetDAO.getOwner())) {
      return;
    }
    throw permissionDenied("READ_METADATA required on Lance table.");
  }

  void authorizeModifyTable(LanceAssetDAO assetDAO) {
    String principal = LanceRequestContext.currentPrincipal();
    if (principal == null || assetDAO.getOwner() == null || principal.equals(assetDAO.getOwner())) {
      return;
    }
    throw permissionDenied("MODIFY required on Lance table.");
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
}
