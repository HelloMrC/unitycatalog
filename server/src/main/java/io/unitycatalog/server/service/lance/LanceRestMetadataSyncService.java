package io.unitycatalog.server.service.lance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import io.unitycatalog.server.auth.UnityCatalogAuthorizer;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.LanceTagRepository;
import io.unitycatalog.server.persist.LanceVersionRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceTagDAO;
import io.unitycatalog.server.persist.dao.LanceVersionDAO;
import java.util.Date;
import java.util.Optional;

@ExceptionHandler(LanceExceptionHandler.class)
public class LanceRestMetadataSyncService {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final LanceTableResolver tableResolver;
  private final LanceVersionRepository versionRepository;
  private final LanceTagRepository tagRepository;
  private final LanceAuthorizationService authorizationService;

  public LanceRestMetadataSyncService(
      Repositories repositories, UnityCatalogAuthorizer authorizer) {
    this.tableResolver = new LanceTableResolver(repositories);
    this.versionRepository = repositories.getLanceVersionRepository();
    this.tagRepository = repositories.getLanceTagRepository();
    this.authorizationService = new LanceAuthorizationService(repositories, authorizer);
  }

  @Post("/v1/table/{id}/metadata/sync/version")
  public HttpResponse syncVersion(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      SyncVersionRequest request) {
    ResolvedLanceTable table =
        resolveActiveNativeTable(id, delimiter.orElse(null), "sync version metadata");
    authorizationService.authorizeModifyTable(table.assetDAO());

    if (request == null || request.version() == null) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance version is required.");
    }

    LanceVersionDAO versionDAO =
        versionRepository.upsertVersion(
            table.assetDAO().getId(),
            request.version(),
            request.operation(),
            request.timestamp() == null ? new Date() : request.timestamp(),
            request.manifestPath(),
            request.manifestSize(),
            request.etag(),
            toJson(request.metadata(), "version metadata"),
            toJson(request.stats(), "version stats"),
            request.createdBy() == null || request.createdBy().isBlank()
                ? currentPrincipal(table)
                : request.createdBy());

    return HttpResponse.ofJson(toSyncVersionResponse(versionDAO));
  }

  @Post("/v1/table/{id}/metadata/sync/tag")
  public HttpResponse syncTag(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      SyncTagRequest request) {
    ResolvedLanceTable table =
        resolveActiveNativeTable(id, delimiter.orElse(null), "sync tag metadata");
    authorizationService.authorizeModifyTable(table.assetDAO());

    if (request == null || request.tagName() == null || request.tagName().isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance tag_name is required.");
    }
    if (request.version() == null) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance tag version is required.");
    }

    LanceTagDAO tagDAO =
        tagRepository.upsertTag(
            table.assetDAO().getId(),
            request.tagName(),
            request.version(),
            toJson(request.metadata(), "tag metadata"),
            request.createdBy() == null || request.createdBy().isBlank()
                ? currentPrincipal(table)
                : request.createdBy());

    return HttpResponse.ofJson(toSyncTagResponse(tagDAO));
  }

  private SyncVersionResponse toSyncVersionResponse(LanceVersionDAO versionDAO) {
    return new SyncVersionResponse(
        versionDAO.getVersion(),
        versionDAO.getOperation(),
        timestampString(versionDAO.getTimestamp()),
        versionDAO.getManifestPath(),
        versionDAO.getManifestSize(),
        versionDAO.getEtag(),
        parseJson(versionDAO.getMetadataJson(), "version metadata_json"),
        parseJson(versionDAO.getStatsJson(), "version stats_json"),
        versionDAO.getCreatedBy(),
        timestampString(versionDAO.getCreatedAt()));
  }

  private SyncTagResponse toSyncTagResponse(LanceTagDAO tagDAO) {
    return new SyncTagResponse(
        tagDAO.getTagName(),
        tagDAO.getVersion(),
        parseJson(tagDAO.getMetadataJson(), "tag metadata_json"),
        timestampString(tagDAO.getCreatedAt()),
        tagDAO.getCreatedBy(),
        timestampString(tagDAO.getUpdatedAt()),
        tagDAO.getUpdatedBy());
  }

  private String timestampString(Date date) {
    return date == null ? null : date.toInstant().toString();
  }

  private ResolvedLanceTable resolveActiveNativeTable(
      String id, String delimiter, String operation) {
    ResolvedLanceTable table = tableResolver.resolve(id, delimiter);
    if (table.legacyBridge()) {
      throw new BaseException(
          ErrorCode.UNIMPLEMENTED, "Legacy bridge Lance tables do not support " + operation + ".");
    }
    if (table.tableRef().declaredOnly()) {
      throw new BaseException(
          ErrorCode.ABORTED, "Declared Lance table must be materialized before " + operation + ".");
    }
    return table;
  }

  private String currentPrincipal(ResolvedLanceTable table) {
    String principal = LanceRequestContext.currentPrincipal();
    if (principal != null && !principal.isBlank()) {
      return principal;
    }
    return table.assetDAO().getOwner();
  }

  private Object parseJson(String json, String fieldName) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return OBJECT_MAPPER.readValue(json, Object.class);
    } catch (JsonProcessingException e) {
      throw new BaseException(ErrorCode.INTERNAL, "Invalid persisted Lance " + fieldName + ".", e);
    }
  }

  private String toJson(Object value, String fieldName) {
    if (value == null) {
      return null;
    }
    try {
      return OBJECT_MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Invalid Lance " + fieldName + ".", e);
    }
  }

  public record SyncVersionRequest(
      Long version,
      String operation,
      Date timestamp,
      @JsonProperty("manifest_path") String manifestPath,
      @JsonProperty("manifest_size") Long manifestSize,
      String etag,
      Object metadata,
      Object stats,
      @JsonProperty("created_by") String createdBy) {}

  public record SyncTagRequest(
      @JsonProperty("tag_name") String tagName,
      Long version,
      Object metadata,
      @JsonProperty("created_by") String createdBy) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SyncVersionResponse(
      Long version,
      String operation,
      String timestamp,
      @JsonProperty("manifest_path") String manifestPath,
      @JsonProperty("manifest_size") Long manifestSize,
      String etag,
      Object metadata,
      Object stats,
      @JsonProperty("created_by") String createdBy,
      @JsonProperty("created_at") String createdAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SyncTagResponse(
      @JsonProperty("tag_name") String tagName,
      Long version,
      Object metadata,
      @JsonProperty("created_at") String createdAt,
      @JsonProperty("created_by") String createdBy,
      @JsonProperty("updated_at") String updatedAt,
      @JsonProperty("updated_by") String updatedBy) {}
}
