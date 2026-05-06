package io.unitycatalog.server.service.lance;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.model.DataSourceFormat;
import io.unitycatalog.server.model.TableInfo;
import io.unitycatalog.server.model.TableType;
import io.unitycatalog.server.persist.LanceTableRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.TableRepository;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.service.lance.backend.LanceTableRef;
import java.util.List;
import java.util.Optional;

class LanceTableResolver {
  private final LanceTableRepository lanceTableRepository;
  private final TableRepository unityTableRepository;
  private final LanceIdentifierCodec identifierCodec;

  LanceTableResolver(Repositories repositories) {
    this.lanceTableRepository = repositories.getLanceTableRepository();
    this.unityTableRepository = repositories.getTableRepository();
    this.identifierCodec = new LanceIdentifierCodec();
  }

  ResolvedLanceTable resolve(String identifier, String delimiter) {
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    if (path.size() < 2) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT, "Table identifier must include namespace and table name");
    }

    String tablePathKey = identifierCodec.toPathKey(path);
    Optional<LanceAssetDAO> assetOpt = lanceTableRepository.findAssetByPathKey(tablePathKey);
    if (assetOpt.isPresent()) {
      return resolveNativeTable(path, tablePathKey, delimiter, assetOpt.get());
    }

    return findLegacyTable(path)
        .map(table -> resolveLegacyTable(path, tablePathKey, delimiter, table))
        .orElseThrow(
            () -> new BaseException(ErrorCode.NOT_FOUND, "Lance table not found: " + identifier));
  }

  private ResolvedLanceTable resolveNativeTable(
      List<String> path, String tablePathKey, String delimiter, LanceAssetDAO assetDAO) {
    Optional<LanceTableDAO> tableOpt = lanceTableRepository.findTableByAssetId(assetDAO.getId());
    LanceTableDAO tableDAO =
        tableOpt.orElseThrow(
            () ->
                new BaseException(
                    ErrorCode.INTERNAL, "Lance table details not found: " + tablePathKey));

    boolean declaredOnly = Boolean.TRUE.equals(tableDAO.getIsOnlyDeclared());
    return new ResolvedLanceTable(
        tableRef(
            path,
            identifierCodec.toExternalIdentifier(tablePathKey, delimiter),
            tablePathKey,
            tableDAO.getStorageLocation(),
            tableDAO.getCurrentVersion(),
            declaredOnly,
            false),
        assetDAO,
        tableDAO,
        false);
  }

  private ResolvedLanceTable resolveLegacyTable(
      List<String> path, String tablePathKey, String delimiter, TableInfo tableInfo) {
    return new ResolvedLanceTable(
        tableRef(
            path,
            identifierCodec.toExternalIdentifier(tablePathKey, delimiter),
            tablePathKey,
            tableInfo.getStorageLocation(),
            null,
            false,
            true),
        null,
        null,
        true);
  }

  private LanceTableRef tableRef(
      List<String> path,
      String externalId,
      String pathKey,
      String storageLocation,
      Long currentVersion,
      boolean declaredOnly,
      boolean legacyBridge) {
    return new LanceTableRef(
        externalId,
        path.subList(0, path.size() - 1),
        path.get(path.size() - 1),
        pathKey,
        storageLocation,
        currentVersion,
        declaredOnly,
        legacyBridge);
  }

  private Optional<TableInfo> findLegacyTable(List<String> path) {
    if (path.size() != 3) {
      return Optional.empty();
    }
    try {
      TableInfo tableInfo = unityTableRepository.getTable(String.join(".", path));
      return isLegacyLanceTable(tableInfo) ? Optional.of(tableInfo) : Optional.empty();
    } catch (BaseException e) {
      if (e.getErrorCode() == ErrorCode.CATALOG_NOT_FOUND
          || e.getErrorCode() == ErrorCode.SCHEMA_NOT_FOUND
          || e.getErrorCode() == ErrorCode.TABLE_NOT_FOUND) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private boolean isLegacyLanceTable(TableInfo tableInfo) {
    return tableInfo.getTableType() == TableType.EXTERNAL
        && tableInfo.getDataSourceFormat() == DataSourceFormat.TEXT
        && tableInfo.getProperties() != null
        && "lance".equalsIgnoreCase(tableInfo.getProperties().get("table_type"));
  }
}
