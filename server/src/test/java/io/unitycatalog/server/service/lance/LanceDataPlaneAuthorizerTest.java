package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.unitycatalog.control.model.User;
import io.unitycatalog.server.auth.UnityCatalogAuthorizer;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.UserRepository;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.model.Privileges;
import io.unitycatalog.server.service.lance.backend.LanceTableRef;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LanceDataPlaneAuthorizerTest {
  private static final String PRINCIPAL = "phase2-reader@example.com";

  @Test
  @DisplayName("READ_DATA accepts SELECT and READ_METADATA compatible grants")
  void readDataAcceptsCompatibleGrants() {
    UUID assetId = UUID.randomUUID();
    UUID principalId = UUID.randomUUID();
    RecordingAuthorizer authorizer = new RecordingAuthorizer();
    authorizer.grantAuthorization(principalId, assetId, Privileges.SELECT);
    LanceDataPlaneAuthorizer dataAuthorizer = dataAuthorizer(authorizer, PRINCIPAL, principalId);

    try (SafeCloseable ignored = pushPrincipal(PRINCIPAL)) {
      LanceDataPlaneAuthorizer.AuthorizationDecision decision =
          dataAuthorizer.authorizeRead(table(assetId, "owner@example.com"));

      assertThat(decision.requiredPrivilege()).isEqualTo("READ_DATA");
      assertThat(decision.compatiblePrivileges())
          .containsExactly(Privileges.SELECT, Privileges.READ_METADATA);
    }
  }

  @Test
  @DisplayName("WRITE_DATA accepts MODIFY compatible grant")
  void writeDataAcceptsModifyGrant() {
    UUID assetId = UUID.randomUUID();
    UUID principalId = UUID.randomUUID();
    RecordingAuthorizer authorizer = new RecordingAuthorizer();
    authorizer.grantAuthorization(principalId, assetId, Privileges.MODIFY);
    LanceDataPlaneAuthorizer dataAuthorizer = dataAuthorizer(authorizer, PRINCIPAL, principalId);

    try (SafeCloseable ignored = pushPrincipal(PRINCIPAL)) {
      LanceDataPlaneAuthorizer.AuthorizationDecision decision =
          dataAuthorizer.authorizeWrite(table(assetId, "owner@example.com"));

      assertThat(decision.requiredPrivilege()).isEqualTo("WRITE_DATA");
      assertThat(decision.compatiblePrivileges()).containsExactly(Privileges.MODIFY);
    }
  }

  @Test
  @DisplayName("READ_DATA grant does not authorize WRITE_DATA")
  void readDataGrantDoesNotAuthorizeWriteData() {
    UUID assetId = UUID.randomUUID();
    UUID principalId = UUID.randomUUID();
    RecordingAuthorizer authorizer = new RecordingAuthorizer();
    authorizer.grantAuthorization(principalId, assetId, Privileges.SELECT);
    LanceDataPlaneAuthorizer dataAuthorizer = dataAuthorizer(authorizer, PRINCIPAL, principalId);

    try (SafeCloseable ignored = pushPrincipal(PRINCIPAL)) {
      assertThatThrownBy(() -> dataAuthorizer.authorizeWrite(table(assetId, "owner@example.com")))
          .isInstanceOf(BaseException.class)
          .extracting(error -> ((BaseException) error).getErrorCode())
          .isEqualTo(ErrorCode.PERMISSION_DENIED);
    }
  }

  private LanceDataPlaneAuthorizer dataAuthorizer(
      UnityCatalogAuthorizer authorizer, String principal, UUID principalId) {
    Repositories repositories = mock(Repositories.class);
    UserRepository userRepository = mock(UserRepository.class);
    when(repositories.getUserRepository()).thenReturn(userRepository);
    when(userRepository.getUserByEmail(principal))
        .thenReturn(new User().id(principalId.toString()).email(principal));

    Properties properties = new Properties();
    properties.setProperty(Property.AUTHORIZATION_ENABLED.getKey(), "enable");
    return new LanceDataPlaneAuthorizer(repositories, authorizer, new ServerProperties(properties));
  }

  private SafeCloseable pushPrincipal(String principal) {
    ServiceRequestContext context = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    context.setAttr(LanceRequestContext.PRINCIPAL_ATTR, principal);
    return context.push();
  }

  private ResolvedLanceTable table(UUID assetId, String owner) {
    LanceAssetDAO assetDAO = LanceAssetDAO.builder().id(assetId).owner(owner).build();
    LanceTableRef tableRef =
        new LanceTableRef(
            "prod$team_a$embeddings",
            List.of("prod", "team_a"),
            "embeddings",
            "prod/team_a/embeddings",
            "file:///tmp/uc-lance/embeddings.lance",
            null,
            false,
            false);
    return new ResolvedLanceTable(tableRef, assetDAO, null, false);
  }

  private static class RecordingAuthorizer implements UnityCatalogAuthorizer {
    private final Set<String> grants = new HashSet<>();

    @Override
    public boolean grantAuthorization(UUID principal, UUID resource, Privileges action) {
      grants.add(key(principal, resource, action));
      return true;
    }

    @Override
    public boolean revokeAuthorization(UUID principal, UUID resource, Privileges action) {
      grants.remove(key(principal, resource, action));
      return true;
    }

    @Override
    public boolean clearAuthorizationsForPrincipal(UUID principal) {
      return true;
    }

    @Override
    public boolean clearAuthorizationsForResource(UUID resource) {
      return true;
    }

    @Override
    public boolean addHierarchyChild(UUID parent, UUID child) {
      return true;
    }

    @Override
    public boolean removeHierarchyChild(UUID parent, UUID child) {
      return true;
    }

    @Override
    public boolean removeHierarchyChildren(UUID resource) {
      return true;
    }

    @Override
    public UUID getHierarchyParent(UUID resource) {
      return null;
    }

    @Override
    public boolean authorize(UUID principal, UUID resource, Privileges action) {
      return grants.contains(key(principal, resource, action));
    }

    @Override
    public boolean authorizeAny(UUID principal, UUID resource, Privileges... actions) {
      return Arrays.stream(actions).anyMatch(action -> authorize(principal, resource, action));
    }

    @Override
    public boolean authorizeAll(UUID principal, UUID resource, Privileges... actions) {
      return Arrays.stream(actions).allMatch(action -> authorize(principal, resource, action));
    }

    @Override
    public List<Privileges> listAuthorizations(UUID principal, UUID resource) {
      return List.of();
    }

    @Override
    public Map<UUID, List<Privileges>> listAuthorizations(UUID resource) {
      return Map.of();
    }

    private String key(UUID principal, UUID resource, Privileges action) {
      return principal + ":" + resource + ":" + action.name();
    }
  }
}
