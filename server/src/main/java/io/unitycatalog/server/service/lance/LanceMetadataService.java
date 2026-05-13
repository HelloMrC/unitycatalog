package io.unitycatalog.server.service.lance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.unitycatalog.server.auth.UnityCatalogAuthorizer;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.model.DataSourceFormat;
import io.unitycatalog.server.model.ListTablesResponse;
import io.unitycatalog.server.model.TableInfo;
import io.unitycatalog.server.model.TableType;
import io.unitycatalog.server.persist.LanceNamespaceRepository;
import io.unitycatalog.server.persist.LanceTableRepository;
import io.unitycatalog.server.persist.MetastoreRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.TableRepository;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceNamespaceDAO;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.utils.IdentityUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class LanceMetadataService {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final List<String> SENSITIVE_STORAGE_OPTION_FRAGMENTS =
      List.of("token", "session", "secret", "expires", "access_key");

  private final LanceNamespaceRepository namespaceRepository;
  private final LanceTableRepository tableRepository;
  private final TableRepository unityTableRepository;
  private final MetastoreRepository metastoreRepository;
  private final LanceIdentifierCodec identifierCodec;
  private final LanceAuthorizationService authorizationService;

  public LanceMetadataService(Repositories repositories, UnityCatalogAuthorizer authorizer) {
    this.namespaceRepository = repositories.getLanceNamespaceRepository();
    this.tableRepository = repositories.getLanceTableRepository();
    this.unityTableRepository = repositories.getTableRepository();
    this.metastoreRepository = repositories.getMetastoreRepository();
    this.identifierCodec = new LanceIdentifierCodec();
    this.authorizationService = new LanceAuthorizationService(repositories, authorizer);
  }

  public NamespaceView createNamespace(
      String identifier, String delimiter, Map<String, String> properties) {
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    int lastIndex = path.size() - 1;
    LanceNamespaceDAO parentNamespace =
        path.size() == 1
            ? null
            : namespaceRepository.getNamespaceOrThrow(
                rootScopeId, identifierCodec.toPathKey(path.subList(0, lastIndex)));
    authorizationService.authorizeCreateNamespace(parentNamespace);
    LanceNamespaceDAO namespaceDAO =
        namespaceRepository.createNamespace(
            rootScopeId,
            parentNamespace == null ? null : parentNamespace.getId(),
            path.get(lastIndex),
            path.size(),
            identifierCodec.toPathKey(path),
            path.get(lastIndex),
            currentOwner(),
            properties);
    authorizationService.initializeNamespaceAuthorization(namespaceDAO, parentNamespace);
    return toNamespaceView(namespaceDAO, delimiter, properties);
  }

  public NamespaceView describeNamespace(String identifier, String delimiter) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    String pathKey = identifierCodec.toPathKey(path);
    LanceNamespaceDAO namespaceDAO = namespaceRepository.getNamespaceOrThrow(rootScopeId, pathKey);
    authorizationService.authorizeReadNamespace(namespaceDAO);
    return toNamespaceView(
        namespaceDAO, delimiter, namespaceRepository.getNamespaceProperties(namespaceDAO.getId()));
  }

  public ExistsResponse namespaceExists(String identifier, String delimiter) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    String pathKey = identifierCodec.toPathKey(path);
    Optional<LanceNamespaceDAO> namespaceDAO =
        namespaceRepository.findNamespace(rootScopeId, pathKey);
    namespaceDAO.ifPresent(authorizationService::authorizeReadNamespace);
    return new ExistsResponse(namespaceDAO.isPresent());
  }

  public NamespaceListResponse listNamespaces(String identifier, String delimiter) {
    return listNamespaces(identifier, delimiter, null, null);
  }

  public NamespaceListResponse listNamespaces(
      String identifier, String delimiter, Integer limit, String pageToken) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    String pathKey = identifierCodec.toPathKey(path);
    LanceNamespaceDAO namespaceDAO = namespaceRepository.getNamespaceOrThrow(rootScopeId, pathKey);
    authorizationService.authorizeReadNamespace(namespaceDAO);
    Integer safeLimit = limit != null && limit > 0 ? limit : null;
    List<LanceNamespaceDAO> children =
        namespaceRepository.listChildNamespaces(
            rootScopeId,
            namespaceDAO.getId(),
            safeLimit == null ? Optional.empty() : Optional.of(safeLimit),
            pageToken == null || pageToken.isBlank() ? Optional.empty() : Optional.of(pageToken));

    String nextPageToken = null;
    if (safeLimit != null && children.size() > safeLimit) {
      nextPageToken = children.get(safeLimit - 1).getName();
      children = children.subList(0, safeLimit);
    }

    List<String> childIds =
        children.stream()
            .map(child -> identifierCodec.toExternalIdentifier(child.getPathKey(), delimiter))
            .toList();
    Map<String, String> contextHeaders = LanceRequestContext.currentContextHeaders();
    return new NamespaceListResponse(
        childIds,
        nextPageToken,
        contextHeaders.isEmpty() ? null : contextHeaders,
        LanceRequestContext.currentPrincipal());
  }

  public DropNamespaceResponse dropNamespace(String identifier, String delimiter, String mode) {
    String effectiveMode = mode == null || mode.isBlank() ? "restrict" : mode;
    if ("cascade".equalsIgnoreCase(effectiveMode)) {
      throw new BaseException(
          ErrorCode.UNIMPLEMENTED, "Cascade namespace drop not supported in current phase.");
    }
    if (!"restrict".equalsIgnoreCase(effectiveMode)) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT, "Unsupported namespace drop mode: " + effectiveMode);
    }

    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    String pathKey = identifierCodec.toPathKey(path);
    LanceNamespaceDAO namespaceDAO = namespaceRepository.getNamespaceOrThrow(rootScopeId, pathKey);
    authorizationService.authorizeModifyNamespace(namespaceDAO);

    if (hasChildNamespaces(rootScopeId, namespaceDAO.getId())
        || hasLanceTables(namespaceDAO.getId())
        || hasLegacyTables(path)) {
      throw new BaseException(ErrorCode.ABORTED, "Lance namespace is not empty: " + identifier);
    }

    namespaceRepository.deleteNamespace(namespaceDAO.getId());
    authorizationService.removeNamespaceAuthorization(namespaceDAO);
    return new DropNamespaceResponse(true);
  }

  private boolean hasChildNamespaces(UUID rootScopeId, UUID namespaceId) {
    return !namespaceRepository
        .listChildNamespaces(rootScopeId, namespaceId, Optional.of(1), Optional.empty())
        .isEmpty();
  }

  private boolean hasLanceTables(UUID namespaceId) {
    return !tableRepository
        .listTables(namespaceId, true, Optional.of(1), Optional.empty())
        .isEmpty();
  }

  private boolean hasLegacyTables(List<String> namespacePath) {
    return listLegacyTables(namespacePath).map(tables -> !tables.isEmpty()).orElse(false);
  }

  private NamespaceView toNamespaceView(
      LanceNamespaceDAO namespaceDAO, String delimiter, Map<String, String> properties) {
    return new NamespaceView(
        identifierCodec.toExternalIdentifier(namespaceDAO.getPathKey(), delimiter), properties);
  }

  // Table operations

  public TableListResponse listTables(
      String identifier,
      String delimiter,
      boolean includeDeclared,
      Integer limit,
      String pageToken) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> namespacePath = identifierCodec.decodeIdentifier(identifier, delimiter);
    String namespacePathKey = identifierCodec.toPathKey(namespacePath);
    Optional<LanceNamespaceDAO> namespaceOpt =
        namespaceRepository.findNamespace(rootScopeId, namespacePathKey);
    namespaceOpt.ifPresent(authorizationService::authorizeReadNamespace);

    Integer safeLimit = limit != null && limit > 0 ? limit : null;
    List<String> tableIds = new ArrayList<>();
    String nextPageToken = null;
    if (namespaceOpt.isPresent()) {
      List<LanceAssetDAO> tables =
          tableRepository.listTables(
              namespaceOpt.get().getId(),
              includeDeclared,
              safeLimit == null ? Optional.empty() : Optional.of(safeLimit),
              pageToken == null || pageToken.isBlank() ? Optional.empty() : Optional.of(pageToken));

      if (safeLimit != null && tables.size() > safeLimit) {
        nextPageToken = tables.get(safeLimit - 1).getName();
        tables = tables.subList(0, safeLimit);
      }

      tableIds.addAll(
          tables.stream()
              .map(asset -> identifierCodec.toExternalIdentifier(asset.getPathKey(), delimiter))
              .toList());
    }

    Optional<List<TableInfo>> legacyTables = listLegacyTables(namespacePath);
    if (legacyTables.isEmpty() && namespaceOpt.isEmpty()) {
      namespaceRepository.getNamespaceOrThrow(rootScopeId, namespacePathKey);
    }
    if ((pageToken == null || pageToken.isBlank()) && nextPageToken == null) {
      // Legacy bridge rows come from UC tables and are appended only on the first native page so a
      // page token cannot interleave two different pagination sources.
      tableIds.addAll(
          legacyTables.orElse(List.of()).stream()
              .map(table -> toLegacyPathKey(namespacePath, table.getName()))
              .map(pathKey -> identifierCodec.toExternalIdentifier(pathKey, delimiter))
              .toList());
    }
    return new TableListResponse(tableIds, nextPageToken);
  }

  public TableView registerTable(
      String identifier, String delimiter, String location, boolean vendCredentials) {
    return registerTable(identifier, delimiter, location, vendCredentials, Map.of(), Map.of());
  }

  public TableView registerTable(
      String identifier,
      String delimiter,
      String location,
      boolean vendCredentials,
      Map<String, String> storageOptionsTemplate,
      Map<String, String> properties) {
    return createTable(
        identifier,
        delimiter,
        location,
        vendCredentials,
        false,
        false,
        storageOptionsTemplate,
        properties);
  }

  public TableView declareTable(
      String identifier,
      String delimiter,
      String location,
      boolean vendCredentials,
      boolean isDeprecatedAlias) {
    return declareTable(
        identifier, delimiter, location, vendCredentials, isDeprecatedAlias, Map.of(), Map.of());
  }

  public TableView declareTable(
      String identifier,
      String delimiter,
      String location,
      boolean vendCredentials,
      boolean isDeprecatedAlias,
      Map<String, String> storageOptionsTemplate,
      Map<String, String> properties) {
    return createTable(
        identifier,
        delimiter,
        location,
        vendCredentials,
        true,
        isDeprecatedAlias,
        storageOptionsTemplate,
        properties);
  }

  private TableView createTable(
      String identifier,
      String delimiter,
      String location,
      boolean vendCredentials,
      boolean isOnlyDeclared,
      boolean isDeprecatedAlias,
      Map<String, String> storageOptionsTemplate,
      Map<String, String> properties) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    if (path.size() < 2) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT, "Table identifier must include namespace and table name");
    }

    int lastIndex = path.size() - 1;
    List<String> namespacePath = path.subList(0, lastIndex);
    String tableName = path.get(lastIndex);
    String namespacePathKey = identifierCodec.toPathKey(namespacePath);
    String tablePathKey = identifierCodec.toPathKey(path);

    LanceNamespaceDAO namespaceDAO =
        namespaceRepository.getNamespaceOrThrow(rootScopeId, namespacePathKey);
    authorizationService.authorizeModifyNamespace(namespaceDAO);
    String owner = currentOwner();
    String canonicalIdentifier = identifierCodec.toExternalIdentifier(tablePathKey, delimiter);

    LanceAssetDAO assetDAO;
    if (isOnlyDeclared) {
      assetDAO =
          tableRepository.declareTable(
              namespaceDAO.getId(),
              tableName,
              tablePathKey,
              canonicalIdentifier,
              location,
              null,
              serializeStorageOptionsTemplate(storageOptionsTemplate),
              properties == null ? Map.of() : properties,
              owner);
    } else {
      assetDAO =
          tableRepository.registerTable(
              namespaceDAO.getId(),
              tableName,
              tablePathKey,
              canonicalIdentifier,
              location,
              null,
              serializeStorageOptionsTemplate(storageOptionsTemplate),
              properties == null ? Map.of() : properties,
              owner);
    }

    Optional<LanceTableDAO> tableDAO = tableRepository.findTableByAssetId(assetDAO.getId());
    authorizationService.initializeTableAuthorization(assetDAO, namespaceDAO);
    return toTableView(
        assetDAO,
        tableDAO.orElse(null),
        delimiter,
        vendCredentials,
        isDeprecatedAlias,
        isOnlyDeclared,
        false,
        isOnlyDeclared ? buildDeclareAudit(isDeprecatedAlias) : null);
  }

  public TableView describeTable(String identifier, String delimiter, boolean vendCredentials) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    String tablePathKey = identifierCodec.toPathKey(path);

    Optional<LanceAssetDAO> assetOpt = tableRepository.findAssetByPathKey(tablePathKey);
    if (assetOpt.isEmpty()) {
      Optional<TableInfo> legacyTable = findLegacyTable(path);
      if (legacyTable.isPresent()) {
        return toLegacyTableView(path, legacyTable.get(), delimiter, vendCredentials);
      }
      throw new BaseException(ErrorCode.NOT_FOUND, "Lance table not found: " + identifier);
    }

    LanceAssetDAO assetDAO = assetOpt.get();
    authorizationService.authorizeReadTable(assetDAO);
    Optional<LanceTableDAO> tableDAO = tableRepository.findTableByAssetId(assetDAO.getId());
    boolean isOnlyDeclared =
        tableDAO.isPresent() && Boolean.TRUE.equals(tableDAO.get().getIsOnlyDeclared());

    return toTableView(
        assetDAO,
        tableDAO.orElse(null),
        delimiter,
        vendCredentials,
        false,
        isOnlyDeclared,
        false,
        null);
  }

  public ExistsResponse tableExists(String identifier, String delimiter) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    String tablePathKey = identifierCodec.toPathKey(path);

    Optional<LanceAssetDAO> assetOpt = tableRepository.findAssetByPathKey(tablePathKey);
    assetOpt.ifPresent(authorizationService::authorizeReadTable);
    return new ExistsResponse(assetOpt.isPresent() || findLegacyTable(path).isPresent());
  }

  public DropTableResponse dropTable(String identifier, String delimiter, String mode) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    String tablePathKey = identifierCodec.toPathKey(path);

    Optional<LanceAssetDAO> assetOpt = tableRepository.findAssetByPathKey(tablePathKey);
    if (assetOpt.isEmpty()) {
      if (findLegacyTable(path).isPresent()) {
        throw new BaseException(
            ErrorCode.UNIMPLEMENTED,
            "Dropping legacy bridge tables not supported in current phase.");
      }
      throw new BaseException(ErrorCode.NOT_FOUND, "Lance table not found: " + identifier);
    }

    LanceAssetDAO assetDAO = assetOpt.get();
    authorizationService.authorizeModifyTable(assetDAO);
    Optional<LanceTableDAO> tableDAO = tableRepository.findTableByAssetId(assetDAO.getId());

    // Phase 1 only supports dropping declared-only tables
    boolean isOnlyDeclared =
        tableDAO.isPresent() && Boolean.TRUE.equals(tableDAO.get().getIsOnlyDeclared());
    if (!isOnlyDeclared) {
      throw new BaseException(
          ErrorCode.UNIMPLEMENTED,
          "Dropping registered tables not supported. Use deregister instead.");
    }

    tableRepository.dropDeclaredTable(assetDAO.getId());
    authorizationService.removeTableAuthorization(assetDAO);
    return new DropTableResponse(true);
  }

  public DeregisterTableResponse deregisterTable(
      String identifier, String delimiter, boolean deletePhysicalData) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    String tablePathKey = identifierCodec.toPathKey(path);

    Optional<LanceAssetDAO> assetOpt = tableRepository.findAssetByPathKey(tablePathKey);
    if (assetOpt.isEmpty()) {
      if (findLegacyTable(path).isPresent()) {
        throw new BaseException(
            ErrorCode.UNIMPLEMENTED,
            "Deregistering legacy bridge tables not supported in current phase.");
      }
      throw new BaseException(ErrorCode.NOT_FOUND, "Lance table not found: " + identifier);
    }

    authorizationService.authorizeModifyTable(assetOpt.get());

    // Phase 1 does not support delete_physical_data=true
    if (deletePhysicalData) {
      throw new BaseException(
          ErrorCode.UNIMPLEMENTED, "Deleting physical data not supported in current phase.");
    }

    tableRepository.deregisterTable(assetOpt.get().getId());
    authorizationService.removeTableAuthorization(assetOpt.get());
    return new DeregisterTableResponse(true);
  }

  /**
   * Rename a Lance table. This is a metadata-only operation that updates the table's path_key,
   * canonical_identifier, name, and namespace_id. The physical storage_location remains unchanged.
   *
   * @param currentIdentifier The current table identifier
   * @param delimiter The delimiter used for identifier parsing
   * @param newTableName The new table name
   * @param newNamespacePath The new namespace path (optional, if null keeps current namespace)
   * @return The updated table view
   */
  public TableView renameTable(
      String currentIdentifier,
      String delimiter,
      String newTableName,
      List<String> newNamespacePath) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> currentPath = identifierCodec.decodeIdentifier(currentIdentifier, delimiter);
    String currentPathKey = identifierCodec.toPathKey(currentPath);

    // Find current table
    Optional<LanceAssetDAO> assetOpt = tableRepository.findAssetByPathKey(currentPathKey);
    if (assetOpt.isEmpty()) {
      if (findLegacyTable(currentPath).isPresent()) {
        throw new BaseException(
            ErrorCode.UNIMPLEMENTED,
            "Renaming legacy bridge tables not supported in current phase.");
      }
      throw new BaseException(ErrorCode.NOT_FOUND, "Lance table not found: " + currentIdentifier);
    }

    LanceAssetDAO assetDAO = assetOpt.get();
    authorizationService.authorizeModifyTable(assetDAO);

    // Check that the table is in a valid state for rename
    String state = assetDAO.getState();
    if (!"ACTIVE".equals(state) && !"DECLARED".equals(state)) {
      throw new BaseException(
          ErrorCode.NOT_FOUND, "Lance table is not active or declared: " + state);
    }

    // Build new path
    List<String> newPath;
    if (newNamespacePath != null && !newNamespacePath.isEmpty()) {
      newPath = new ArrayList<>(newNamespacePath);
    } else {
      // Keep current namespace
      int lastIndex = currentPath.size() - 1;
      newPath = new ArrayList<>(currentPath.subList(0, lastIndex));
    }
    newPath.add(newTableName);
    String newPathKey = identifierCodec.toPathKey(newPath);

    // Find new namespace
    int newLastIndex = newPath.size() - 1;
    List<String> newNamespacePathKeyPath = newPath.subList(0, newLastIndex);
    String newNamespacePathKey = identifierCodec.toPathKey(newNamespacePathKeyPath);
    LanceNamespaceDAO newNamespaceDAO =
        namespaceRepository.getNamespaceOrThrow(rootScopeId, newNamespacePathKey);
    authorizationService.authorizeModifyNamespace(newNamespaceDAO);

    // Perform rename
    String newCanonicalIdentifier = identifierCodec.toExternalIdentifier(newPathKey, delimiter);
    tableRepository.renameTable(
        assetDAO.getId(),
        newPathKey,
        newCanonicalIdentifier,
        newTableName,
        newNamespaceDAO.getId(),
        currentOwner());

    // Return updated table view
    Optional<LanceAssetDAO> updatedAssetOpt = tableRepository.findAssetByPathKey(newPathKey);
    Optional<LanceTableDAO> updatedTableOpt =
        tableRepository.findTableByAssetId(updatedAssetOpt.get().getId());
    return toTableView(
        updatedAssetOpt.get(),
        updatedTableOpt.orElse(null),
        delimiter,
        false,
        false,
        updatedTableOpt.isPresent()
            && Boolean.TRUE.equals(updatedTableOpt.get().getIsOnlyDeclared()),
        false,
        null);
  }

  private TableView toTableView(
      LanceAssetDAO assetDAO,
      LanceTableDAO tableDAO,
      String delimiter,
      boolean vendCredentials,
      boolean isDeprecatedAlias,
      boolean isOnlyDeclared,
      boolean legacyBridge,
      Map<String, Object> audit) {
    String location = tableDAO != null ? tableDAO.getStorageLocation() : null;
    String state = assetDAO.getState();
    Map<String, String> storageOptionsTemplate = parseStorageOptionsTemplate(tableDAO);

    return new TableView(
        identifierCodec.toExternalIdentifier(assetDAO.getPathKey(), delimiter),
        location,
        state,
        isOnlyDeclared,
        vendCredentials ? buildStorageOptions(storageOptionsTemplate) : null,
        storageOptionsTemplate.isEmpty() ? null : storageOptionsTemplate,
        null,
        null,
        isDeprecatedAlias ? "create-empty" : null,
        isDeprecatedAlias,
        legacyBridge,
        isOnlyDeclared ? false : null,
        LanceRequestContext.currentPrincipal(),
        authorizationService.authorizationMetadata(),
        audit);
  }

  private TableView toLegacyTableView(
      List<String> path, TableInfo tableInfo, String delimiter, boolean vendCredentials) {
    return new TableView(
        identifierCodec.toExternalIdentifier(identifierCodec.toPathKey(path), delimiter),
        tableInfo.getStorageLocation(),
        "ACTIVE",
        false,
        vendCredentials ? Map.of() : null,
        null,
        tableInfo.getProperties(),
        String.join(".", path),
        null,
        false,
        true,
        false,
        LanceRequestContext.currentPrincipal(),
        authorizationService.authorizationMetadata(),
        null);
  }

  private String currentOwner() {
    String lancePrincipal = LanceRequestContext.currentPrincipal();
    return lancePrincipal != null ? lancePrincipal : IdentityUtils.findPrincipalEmailAddress();
  }

  private Map<String, Object> buildDeclareAudit(boolean isDeprecatedAlias) {
    return Map.of(
        "protocol_operation",
        "declare_table",
        "protocol_variant",
        isDeprecatedAlias ? "create-empty" : "declare",
        "deprecated_alias_used",
        isDeprecatedAlias);
  }

  private Optional<TableInfo> findLegacyTable(List<String> path) {
    if (path.size() != 3) {
      return Optional.empty();
    }
    try {
      // Legacy Lance tables are ordinary UC external TEXT tables marked with table_type=lance.
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

  private Optional<List<TableInfo>> listLegacyTables(List<String> namespacePath) {
    if (namespacePath.size() != 2) {
      return Optional.empty();
    }
    try {
      // UC legacy lookup is limited to catalog.schema because Unity tables are fixed at three
      // levels, unlike Lance namespace paths which may be deeper.
      ListTablesResponse response =
          unityTableRepository.listTables(
              namespacePath.get(0),
              namespacePath.get(1),
              Optional.empty(),
              Optional.empty(),
              false,
              true);
      return Optional.of(response.getTables().stream().filter(this::isLegacyLanceTable).toList());
    } catch (BaseException e) {
      if (e.getErrorCode() == ErrorCode.CATALOG_NOT_FOUND
          || e.getErrorCode() == ErrorCode.SCHEMA_NOT_FOUND) {
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

  private String toLegacyPathKey(List<String> namespacePath, String tableName) {
    List<String> path = new ArrayList<>(namespacePath);
    path.add(tableName);
    return identifierCodec.toPathKey(path);
  }

  private String serializeStorageOptionsTemplate(Map<String, String> storageOptionsTemplate) {
    Map<String, String> sanitized = sanitizeStorageOptionsTemplate(storageOptionsTemplate);
    if (sanitized.isEmpty()) {
      return null;
    }
    try {
      return OBJECT_MAPPER.writeValueAsString(sanitized);
    } catch (JsonProcessingException e) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT, "Invalid storage_options_template: " + e.getMessage());
    }
  }

  private Map<String, String> parseStorageOptionsTemplate(LanceTableDAO tableDAO) {
    if (tableDAO == null
        || tableDAO.getStorageOptionsTemplateJson() == null
        || tableDAO.getStorageOptionsTemplateJson().isBlank()) {
      return Map.of();
    }
    try {
      Map<?, ?> raw = OBJECT_MAPPER.readValue(tableDAO.getStorageOptionsTemplateJson(), Map.class);
      return sanitizeStorageOptionsTemplate(
          raw.entrySet().stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      entry -> String.valueOf(entry.getKey()),
                      entry -> String.valueOf(entry.getValue()))));
    } catch (JsonProcessingException e) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT, "Invalid persisted storage_options_template");
    }
  }

  private Map<String, String> sanitizeStorageOptionsTemplate(
      Map<String, String> storageOptionsTemplate) {
    if (storageOptionsTemplate == null || storageOptionsTemplate.isEmpty()) {
      return Map.of();
    }
    // Persist only stable, non-secret storage options. Runtime credentials are vended per request.
    return storageOptionsTemplate.entrySet().stream()
        .filter(entry -> !isSensitiveStorageOption(entry.getKey()))
        .collect(
            java.util.stream.Collectors.toMap(
                Map.Entry::getKey, entry -> entry.getValue() == null ? "" : entry.getValue()));
  }

  private boolean isSensitiveStorageOption(String key) {
    String normalizedKey = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
    return SENSITIVE_STORAGE_OPTION_FRAGMENTS.stream().anyMatch(normalizedKey::contains);
  }

  private Map<String, String> buildStorageOptions(Map<String, String> storageOptionsTemplate) {
    return storageOptionsTemplate == null ? Map.of() : storageOptionsTemplate;
  }

  public record NamespaceView(String id, Map<String, String> properties) {}

  public record ExistsResponse(boolean exists) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record NamespaceListResponse(
      List<String> namespaces,
      String nextPageToken,
      Map<String, String> context,
      String principal) {}

  public record DropNamespaceResponse(boolean dropped) {}

  public record TableListResponse(List<String> tables, String nextPageToken) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TableView(
      String id,
      String location,
      String state,
      @JsonProperty("is_only_declared") boolean isOnlyDeclared,
      @JsonProperty("storage_options") Map<String, String> storageOptions,
      @JsonProperty("storage_options_template") Map<String, String> storageOptionsTemplate,
      Map<String, String> properties,
      @JsonProperty("source_table_full_name") String sourceTableFullName,
      @JsonProperty("protocol_variant") String protocolVariant,
      @JsonProperty("deprecated_alias_used") boolean deprecatedAliasUsed,
      @JsonProperty("legacy_bridge") boolean legacyBridge,
      @JsonProperty("physical_metadata_loaded") Boolean physicalMetadataLoaded,
      String principal,
      Map<String, Object> authorization,
      Map<String, Object> audit) {}

  public record DropTableResponse(boolean dropped) {}

  public record DeregisterTableResponse(boolean deregistered) {}
}
