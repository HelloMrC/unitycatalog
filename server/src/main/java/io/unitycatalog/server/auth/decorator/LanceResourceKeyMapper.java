package io.unitycatalog.server.auth.decorator;

import static io.unitycatalog.server.model.SecurableType.LANCE_NAMESPACE;
import static io.unitycatalog.server.model.SecurableType.LANCE_TABLE;

import io.unitycatalog.server.model.SecurableType;
import io.unitycatalog.server.persist.LanceNamespaceRepository;
import io.unitycatalog.server.persist.LanceTableRepository;
import io.unitycatalog.server.persist.MetastoreRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceNamespaceDAO;
import io.unitycatalog.server.service.lance.LanceIdentifierCodec;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class LanceResourceKeyMapper {
  private final LanceNamespaceRepository namespaceRepository;
  private final LanceTableRepository tableRepository;
  private final MetastoreRepository metastoreRepository;
  private final LanceIdentifierCodec identifierCodec;

  public LanceResourceKeyMapper(Repositories repositories) {
    this.namespaceRepository = repositories.getLanceNamespaceRepository();
    this.tableRepository = repositories.getLanceTableRepository();
    this.metastoreRepository = repositories.getMetastoreRepository();
    this.identifierCodec = new LanceIdentifierCodec();
  }

  public Map<SecurableType, Object> mapResourceKeys(Map<SecurableType, Object> resourceKeys) {
    Map<SecurableType, Object> resourceIds = new HashMap<>();
    if (resourceKeys.containsKey(LANCE_NAMESPACE)) {
      resourceIds.put(
          LANCE_NAMESPACE,
          resolveNamespaceId(resourceKeys.get(LANCE_NAMESPACE)).orElse(null));
    }
    if (resourceKeys.containsKey(LANCE_TABLE)) {
      Optional<LanceAssetDAO> asset = resolveTableAsset(resourceKeys.get(LANCE_TABLE));
      asset.ifPresent(
          lanceAssetDAO -> {
            resourceIds.put(LANCE_TABLE, lanceAssetDAO.getId());
            resourceIds.put(LANCE_NAMESPACE, lanceAssetDAO.getNamespaceId());
          });
    }
    return resourceIds;
  }

  public Optional<UUID> resolveNamespaceId(Object namespaceIdentifier) {
    if (namespaceIdentifier instanceof UUID namespaceId) {
      return Optional.of(namespaceId);
    }
    String pathKey = toPathKey(namespaceIdentifier);
    return namespaceRepository
        .findNamespace(metastoreRepository.getMetastoreId(), pathKey)
        .map(LanceNamespaceDAO::getId);
  }

  public Optional<LanceAssetDAO> resolveTableAsset(Object tableIdentifier) {
    if (tableIdentifier instanceof UUID tableId) {
      return tableRepository.findAssetById(tableId);
    }
    return tableRepository.findAssetByPathKey(toPathKey(tableIdentifier));
  }

  private String toPathKey(Object identifier) {
    return identifierCodec.toPathKey(
        identifierCodec.decodeIdentifier(String.valueOf(identifier), null));
  }
}
