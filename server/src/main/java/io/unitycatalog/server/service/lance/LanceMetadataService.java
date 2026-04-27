package io.unitycatalog.server.service.lance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.LanceNamespaceRepository;
import io.unitycatalog.server.persist.LanceTableRepository;
import io.unitycatalog.server.persist.MetastoreRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceNamespaceDAO;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.utils.IdentityUtils;
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
  private final MetastoreRepository metastoreRepository;
  private final LanceIdentifierCodec identifierCodec;

  public LanceMetadataService(Repositories repositories) {
    this.namespaceRepository = repositories.getLanceNamespaceRepository();
    this.tableRepository = repositories.getLanceTableRepository();
    this.metastoreRepository = repositories.getMetastoreRepository();
    this.identifierCodec = new LanceIdentifierCodec();
  }

  public NamespaceView createNamespace(
      String identifier, String delimiter, Map<String, String> properties) {
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    int lastIndex = path.size() - 1;
    UUID parentNamespaceId =
        path.size() == 1
            ? null
            : namespaceRepository
                .getNamespaceOrThrow(
                    rootScopeId, identifierCodec.toPathKey(path.subList(0, lastIndex)))
                .getId();
    LanceNamespaceDAO namespaceDAO =
        namespaceRepository.createNamespace(
            rootScopeId,
            parentNamespaceId,
            path.get(lastIndex),
            path.size(),
            identifierCodec.toPathKey(path),
            path.get(lastIndex),
            IdentityUtils.findPrincipalEmailAddress(),
            properties);
    return toNamespaceView(namespaceDAO, delimiter, properties);
  }

  public NamespaceView describeNamespace(String identifier, String delimiter) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    String pathKey = identifierCodec.toPathKey(path);
    LanceNamespaceDAO namespaceDAO = namespaceRepository.getNamespaceOrThrow(rootScopeId, pathKey);
    return toNamespaceView(
        namespaceDAO, delimiter, namespaceRepository.getNamespaceProperties(namespaceDAO.getId()));
  }

  public ExistsResponse namespaceExists(String identifier, String delimiter) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    String pathKey = identifierCodec.toPathKey(path);
    return new ExistsResponse(namespaceRepository.findNamespace(rootScopeId, pathKey).isPresent());
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
    return new NamespaceListResponse(childIds, nextPageToken);
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
    LanceNamespaceDAO namespaceDAO =
        namespaceRepository.getNamespaceOrThrow(rootScopeId, namespacePathKey);

    Integer safeLimit = limit != null && limit > 0 ? limit : null;
    List<LanceAssetDAO> tables =
        tableRepository.listTables(
            namespaceDAO.getId(),
            includeDeclared,
            safeLimit == null ? Optional.empty() : Optional.of(safeLimit),
            pageToken == null || pageToken.isBlank() ? Optional.empty() : Optional.of(pageToken));

    String nextPageToken = null;
    if (safeLimit != null && tables.size() > safeLimit) {
      nextPageToken = tables.get(safeLimit - 1).getName();
      tables = tables.subList(0, safeLimit);
    }

    List<String> tableIds =
        tables.stream()
            .map(asset -> identifierCodec.toExternalIdentifier(asset.getPathKey(), delimiter))
            .toList();
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
    String owner = IdentityUtils.findPrincipalEmailAddress();
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
    return toTableView(
        assetDAO,
        tableDAO.orElse(null),
        delimiter,
        vendCredentials,
        isDeprecatedAlias,
        isOnlyDeclared,
        false);
  }

  public TableView describeTable(String identifier, String delimiter, boolean vendCredentials) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    String tablePathKey = identifierCodec.toPathKey(path);

    Optional<LanceAssetDAO> assetOpt = tableRepository.findAssetByPathKey(tablePathKey);
    if (assetOpt.isEmpty()) {
      throw new BaseException(ErrorCode.NOT_FOUND, "Lance table not found: " + identifier);
    }

    LanceAssetDAO assetDAO = assetOpt.get();
    Optional<LanceTableDAO> tableDAO = tableRepository.findTableByAssetId(assetDAO.getId());
    boolean isOnlyDeclared =
        tableDAO.isPresent() && Boolean.TRUE.equals(tableDAO.get().getIsOnlyDeclared());

    return toTableView(
        assetDAO, tableDAO.orElse(null), delimiter, vendCredentials, false, isOnlyDeclared, false);
  }

  public ExistsResponse tableExists(String identifier, String delimiter) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    String tablePathKey = identifierCodec.toPathKey(path);

    Optional<LanceAssetDAO> assetOpt = tableRepository.findAssetByPathKey(tablePathKey);
    return new ExistsResponse(assetOpt.isPresent());
  }

  public DropTableResponse dropTable(String identifier, String delimiter, String mode) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    String tablePathKey = identifierCodec.toPathKey(path);

    Optional<LanceAssetDAO> assetOpt = tableRepository.findAssetByPathKey(tablePathKey);
    if (assetOpt.isEmpty()) {
      throw new BaseException(ErrorCode.NOT_FOUND, "Lance table not found: " + identifier);
    }

    LanceAssetDAO assetDAO = assetOpt.get();
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
    return new DropTableResponse(true);
  }

  public DeregisterTableResponse deregisterTable(
      String identifier, String delimiter, boolean deletePhysicalData) {
    UUID rootScopeId = metastoreRepository.getMetastoreId();
    List<String> path = identifierCodec.decodeIdentifier(identifier, delimiter);
    String tablePathKey = identifierCodec.toPathKey(path);

    Optional<LanceAssetDAO> assetOpt = tableRepository.findAssetByPathKey(tablePathKey);
    if (assetOpt.isEmpty()) {
      throw new BaseException(ErrorCode.NOT_FOUND, "Lance table not found: " + identifier);
    }

    // Phase 1 does not support delete_physical_data=true
    if (deletePhysicalData) {
      throw new BaseException(
          ErrorCode.UNIMPLEMENTED, "Deleting physical data not supported in current phase.");
    }

    tableRepository.deregisterTable(assetOpt.get().getId());
    return new DeregisterTableResponse(true);
  }

  private TableView toTableView(
      LanceAssetDAO assetDAO,
      LanceTableDAO tableDAO,
      String delimiter,
      boolean vendCredentials,
      boolean isDeprecatedAlias,
      boolean isOnlyDeclared,
      boolean legacyBridge) {
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
        isDeprecatedAlias ? "create-empty" : null,
        isDeprecatedAlias,
        legacyBridge,
        isOnlyDeclared ? false : null);
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

  public record NamespaceListResponse(List<String> namespaces, String nextPageToken) {}

  public record TableListResponse(List<String> tables, String nextPageToken) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TableView(
      String id,
      String location,
      String state,
      @JsonProperty("is_only_declared") boolean isOnlyDeclared,
      @JsonProperty("storage_options") Map<String, String> storageOptions,
      @JsonProperty("storage_options_template") Map<String, String> storageOptionsTemplate,
      @JsonProperty("protocol_variant") String protocolVariant,
      @JsonProperty("deprecated_alias_used") boolean deprecatedAliasUsed,
      @JsonProperty("legacy_bridge") boolean legacyBridge,
      @JsonProperty("physical_metadata_loaded") Boolean physicalMetadataLoaded) {}

  public record DropTableResponse(boolean dropped) {}

  public record DeregisterTableResponse(boolean deregistered) {}
}
