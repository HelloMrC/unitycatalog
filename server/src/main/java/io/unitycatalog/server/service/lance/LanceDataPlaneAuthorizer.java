package io.unitycatalog.server.service.lance;

import io.unitycatalog.server.auth.UnityCatalogAuthorizer;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.UserRepository;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.model.Privileges;
import io.unitycatalog.server.utils.ServerProperties;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class LanceDataPlaneAuthorizer {
  private static final String READ_DATA = "READ_DATA";
  private static final String WRITE_DATA = "WRITE_DATA";

  private final UnityCatalogAuthorizer authorizer;
  private final UserRepository userRepository;
  private final ServerProperties serverProperties;

  LanceDataPlaneAuthorizer(
      Repositories repositories,
      UnityCatalogAuthorizer authorizer,
      ServerProperties serverProperties) {
    this.authorizer = authorizer;
    this.userRepository = repositories.getUserRepository();
    this.serverProperties = serverProperties;
  }

  AuthorizationDecision authorizeRead(ResolvedLanceTable table) {
    authorize(table, READ_DATA, Privileges.SELECT, Privileges.READ_METADATA);
    return new AuthorizationDecision(
        READ_DATA, List.of(Privileges.SELECT, Privileges.READ_METADATA));
  }

  AuthorizationDecision authorizeMetadataRead(ResolvedLanceTable table) {
    authorize(table, "READ_METADATA", Privileges.READ_METADATA);
    return new AuthorizationDecision("READ_METADATA", List.of(Privileges.READ_METADATA));
  }

  AuthorizationDecision authorizeWrite(ResolvedLanceTable table) {
    authorize(table, WRITE_DATA, Privileges.MODIFY);
    return new AuthorizationDecision(WRITE_DATA, List.of(Privileges.MODIFY));
  }

  private void authorize(
      ResolvedLanceTable table, String requiredPrivilege, Privileges... compatiblePrivileges) {
    if (!serverProperties.isAuthorizationEnabled()) {
      return;
    }

    String principal = LanceRequestContext.currentPrincipal();
    if (principal == null || principal.isBlank()) {
      throw permissionDenied(requiredPrivilege);
    }
    if (table.assetDAO() == null) {
      throw permissionDenied(requiredPrivilege);
    }

    LanceAssetDAO assetDAO = table.assetDAO();
    if (principal.equals(assetDAO.getOwner())) {
      return;
    }

    Optional<UUID> principalId = principalId(principal);
    if (principalId.isPresent()
        && authorizer.authorizeAny(
            principalId.get(), assetDAO.getId(), prependOwner(compatiblePrivileges))) {
      return;
    }
    throw permissionDenied(requiredPrivilege);
  }

  private Privileges[] prependOwner(Privileges[] privileges) {
    Privileges[] withOwner = new Privileges[privileges.length + 1];
    withOwner[0] = Privileges.OWNER;
    System.arraycopy(privileges, 0, withOwner, 1, privileges.length);
    return withOwner;
  }

  private Optional<UUID> principalId(String principal) {
    try {
      return Optional.of(UUID.fromString(userRepository.getUserByEmail(principal).getId()));
    } catch (BaseException e) {
      if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private BaseException permissionDenied(String requiredPrivilege) {
    return new BaseException(
        ErrorCode.PERMISSION_DENIED, requiredPrivilege + " required on Lance table.");
  }

  record AuthorizationDecision(String requiredPrivilege, List<Privileges> compatiblePrivileges) {}
}
