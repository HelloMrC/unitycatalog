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
import java.util.List;
import java.util.Optional;

@ExceptionHandler(LanceExceptionHandler.class)
public class LanceRestTagService {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final LanceTableResolver tableResolver;
  private final LanceTagRepository tagRepository;
  private final LanceVersionRepository versionRepository;
  private final LanceAuthorizationService authorizationService;

  public LanceRestTagService(Repositories repositories, UnityCatalogAuthorizer authorizer) {
    this.tableResolver = new LanceTableResolver(repositories);
    this.tagRepository = repositories.getLanceTagRepository();
    this.versionRepository = repositories.getLanceVersionRepository();
    this.authorizationService = new LanceAuthorizationService(repositories, authorizer);
  }

  @Post("/v1/table/{id}/tags/list")
  public HttpResponse listTags(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      TagListRequest request) {
    ResolvedLanceTable table = resolveNativeTable(id, delimiter.orElse(null), "tag metadata");
    authorizationService.authorizeReadTable(table.assetDAO());

    Integer safeLimit = safeLimit(request == null ? null : request.pageSize());
    List<LanceTagDAO> tags =
        tagRepository.listTags(
            table.assetDAO().getId(),
            safeLimit == null ? Optional.empty() : Optional.of(safeLimit),
            optionalString(request == null ? null : request.pageToken()));

    String nextPageToken = null;
    if (safeLimit != null && tags.size() > safeLimit) {
      nextPageToken = tags.get(safeLimit - 1).getTagName();
      tags = tags.subList(0, safeLimit);
    }

    return HttpResponse.ofJson(
        new TagListResponse(tags.stream().map(this::toTagView).toList(), nextPageToken));
  }

  @Post("/v1/table/{id}/tags/get")
  public HttpResponse getTag(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      TagNameRequest request) {
    return getTagResponse(id, delimiter.orElse(null), request);
  }

  @Post("/v1/table/{id}/tags/get-version")
  public HttpResponse getTagVersion(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      TagNameRequest request) {
    return getTagResponse(id, delimiter.orElse(null), request);
  }

  @Post("/v1/table/{id}/tags/version")
  public HttpResponse getTagVersionAlias(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      TagNameRequest request) {
    return getTagResponse(id, delimiter.orElse(null), request);
  }

  @Post("/v1/table/{id}/tags/create")
  public HttpResponse createTag(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      TagCreateRequest request) {
    ResolvedLanceTable table =
        resolveActiveNativeTable(id, delimiter.orElse(null), "create tags");
    authorizationService.authorizeModifyTable(table.assetDAO());
    Long version = request == null ? null : request.version();
    requireVersion(table, version);

    LanceTagDAO tagDAO =
        tagRepository.createTag(
            table.assetDAO().getId(),
            tagName(
                request == null ? null : request.tagName(),
                request == null ? null : request.tag()),
            version,
            toJson(request == null ? null : request.metadata(), "tag metadata"),
            currentPrincipal(table));
    return HttpResponse.ofJson(toTagView(tagDAO));
  }

  @Post("/v1/table/{id}/tags/update")
  public HttpResponse updateTag(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      TagUpdateRequest request) {
    ResolvedLanceTable table =
        resolveActiveNativeTable(id, delimiter.orElse(null), "update tags");
    authorizationService.authorizeModifyTable(table.assetDAO());
    Long version = request == null ? null : request.effectiveVersion();
    requireVersion(table, version);

    LanceTagDAO tagDAO =
        tagRepository.updateTag(
            table.assetDAO().getId(),
            tagName(
                request == null ? null : request.tagName(),
                request == null ? null : request.tag()),
            version,
            toJson(request == null ? null : request.effectiveMetadata(), "tag metadata"),
            currentPrincipal(table));
    return HttpResponse.ofJson(toTagView(tagDAO));
  }

  @Post("/v1/table/{id}/tags/delete")
  public HttpResponse deleteTag(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      TagNameRequest request) {
    ResolvedLanceTable table =
        resolveActiveNativeTable(id, delimiter.orElse(null), "delete tags");
    authorizationService.authorizeModifyTable(table.assetDAO());
    String tagName =
        tagName(
            request == null ? null : request.tagName(), request == null ? null : request.tag());
    tagRepository.deleteTag(table.assetDAO().getId(), tagName);
    return HttpResponse.ofJson(new TagDeleteResponse(tagName, true));
  }

  private HttpResponse getTagResponse(String id, String delimiter, TagNameRequest request) {
    ResolvedLanceTable table = resolveNativeTable(id, delimiter, "tag metadata");
    authorizationService.authorizeReadTable(table.assetDAO());
    String tagName =
        tagName(
            request == null ? null : request.tagName(), request == null ? null : request.tag());
    LanceTagDAO tagDAO =
        tagRepository
            .findTag(table.assetDAO().getId(), tagName)
            .orElseThrow(
                () -> new BaseException(ErrorCode.NOT_FOUND, "Lance tag not found: " + tagName));
    return HttpResponse.ofJson(toTagView(tagDAO));
  }

  private ResolvedLanceTable resolveActiveNativeTable(
      String id, String delimiter, String operation) {
    ResolvedLanceTable table = resolveNativeTable(id, delimiter, operation);
    if (table.tableRef().declaredOnly()) {
      throw new BaseException(
          ErrorCode.ABORTED,
          "Declared Lance table must be materialized before " + operation + ".");
    }
    return table;
  }

  private ResolvedLanceTable resolveNativeTable(String id, String delimiter, String operation) {
    ResolvedLanceTable table = tableResolver.resolve(id, delimiter);
    if (table.legacyBridge()) {
      throw new BaseException(
          ErrorCode.UNIMPLEMENTED, "Legacy bridge Lance tables do not support " + operation + ".");
    }
    return table;
  }

  private void requireVersion(ResolvedLanceTable table, Long version) {
    if (version == null) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance tag version is required.");
    }
    if (!versionRepository.versionExists(table.assetDAO().getId(), version)) {
      throw new BaseException(ErrorCode.NOT_FOUND, "Lance version not found: " + version);
    }
  }

  private String tagName(String tagName, String tag) {
    String resolved = tagName == null || tagName.isBlank() ? tag : tagName;
    if (resolved == null || resolved.isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance tag_name is required.");
    }
    return resolved;
  }

  private String currentPrincipal(ResolvedLanceTable table) {
    String principal = LanceRequestContext.currentPrincipal();
    if (principal != null && !principal.isBlank()) {
      return principal;
    }
    return table.assetDAO().getOwner();
  }

  private TagView toTagView(LanceTagDAO tagDAO) {
    return new TagView(
        tagDAO.getTagName(),
        tagDAO.getVersion(),
        parseJson(tagDAO.getMetadataJson(), "tag metadata_json"),
        tagDAO.getCreatedAt() == null ? null : tagDAO.getCreatedAt().toInstant().toString(),
        tagDAO.getCreatedBy(),
        tagDAO.getUpdatedAt() == null ? null : tagDAO.getUpdatedAt().toInstant().toString(),
        tagDAO.getUpdatedBy());
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

  private Integer safeLimit(Integer limit) {
    return limit != null && limit > 0 ? limit : null;
  }

  private Optional<String> optionalString(String value) {
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
  }

  public record TagListRequest(
      @JsonProperty("page_token") String pageToken, @JsonProperty("page_size") Integer pageSize) {}

  public record TagNameRequest(@JsonProperty("tag_name") String tagName, String tag) {}

  public record TagCreateRequest(
      @JsonProperty("tag_name") String tagName, String tag, Long version, Object metadata) {}

  public record TagUpdateRequest(
      @JsonProperty("tag_name") String tagName,
      String tag,
      @JsonProperty("new_version") Long newVersion,
      Long version,
      @JsonProperty("new_metadata") Object newMetadata,
      Object metadata) {
    private Long effectiveVersion() {
      return newVersion == null ? version : newVersion;
    }

    private Object effectiveMetadata() {
      return newMetadata == null ? metadata : newMetadata;
    }
  }

  public record TagListResponse(
      List<TagView> tags, @JsonProperty("next_page_token") String nextPageToken) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TagView(
      @JsonProperty("tag_name") String tagName,
      Long version,
      Object metadata,
      @JsonProperty("created_at") String createdAt,
      @JsonProperty("created_by") String createdBy,
      @JsonProperty("updated_at") String updatedAt,
      @JsonProperty("updated_by") String updatedBy) {}

  public record TagDeleteResponse(@JsonProperty("tag_name") String tagName, boolean deleted) {}
}
