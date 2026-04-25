package io.unitycatalog.server.service.lance;

import io.unitycatalog.server.persist.LanceNamespaceRepository;
import io.unitycatalog.server.persist.MetastoreRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceNamespaceDAO;
import io.unitycatalog.server.utils.IdentityUtils;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LanceMetadataService {
  private final LanceNamespaceRepository namespaceRepository;
  private final MetastoreRepository metastoreRepository;
  private final LanceIdentifierCodec identifierCodec;

  public LanceMetadataService(Repositories repositories) {
    this.namespaceRepository = repositories.getLanceNamespaceRepository();
    this.metastoreRepository = repositories.getMetastoreRepository();
    this.identifierCodec = new LanceIdentifierCodec();
  }

  public NamespaceView createNamespace(
      String identifier, String delimiter, Map<String, String> properties) {
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    UUID parentNamespaceId =
        path.size() == 1
            ? null
            : namespaceRepository
                .getNamespaceOrThrow(rootScopeId, identifierCodec.toPathKey(path.subList(0, path.size() - 1)))
                .getId();
    LanceNamespaceDAO namespaceDAO =
        namespaceRepository.createNamespace(
            rootScopeId,
            parentNamespaceId,
            path.get(path.size() - 1),
            path.size(),
            identifierCodec.toPathKey(path),
            path.get(path.size() - 1),
            IdentityUtils.findPrincipalEmailAddress(),
            properties);
    return toNamespaceView(namespaceDAO, delimiter, properties);
  }

  public NamespaceView describeNamespace(String identifier, String delimiter) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    String pathKey = identifierCodec.toPathKey(identifierCodec.decodeIdentifier(identifier, delimiter));
    LanceNamespaceDAO namespaceDAO = namespaceRepository.getNamespaceOrThrow(rootScopeId, pathKey);
    return toNamespaceView(
        namespaceDAO, delimiter, namespaceRepository.getNamespaceProperties(namespaceDAO.getId()));
  }

  public ExistsResponse namespaceExists(String identifier, String delimiter) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    String pathKey = identifierCodec.toPathKey(identifierCodec.decodeIdentifier(identifier, delimiter));
    return new ExistsResponse(namespaceRepository.findNamespace(rootScopeId, pathKey).isPresent());
  }

  public NamespaceListResponse listNamespaces(String identifier, String delimiter) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    String pathKey = identifierCodec.toPathKey(identifierCodec.decodeIdentifier(identifier, delimiter));
    LanceNamespaceDAO namespaceDAO = namespaceRepository.getNamespaceOrThrow(rootScopeId, pathKey);
    List<String> childIds =
        namespaceRepository.listChildNamespaces(rootScopeId, namespaceDAO.getId()).stream()
            .map(child -> identifierCodec.toExternalIdentifier(child.getPathKey(), delimiter))
            .toList();
    return new NamespaceListResponse(childIds, null);
  }

  private NamespaceView toNamespaceView(
      LanceNamespaceDAO namespaceDAO, String delimiter, Map<String, String> properties) {
    return new NamespaceView(
        identifierCodec.toExternalIdentifier(namespaceDAO.getPathKey(), delimiter), properties);
  }

  public record NamespaceView(String id, Map<String, String> properties) {}

  public record ExistsResponse(boolean exists) {}

  public record NamespaceListResponse(List<String> namespaces, String nextPageToken) {}
}
