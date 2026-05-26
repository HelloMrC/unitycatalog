package io.unitycatalog.server.service.lance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import io.unitycatalog.server.auth.UnityCatalogAuthorizer;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.LanceVersionRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceVersionDAO;
import io.unitycatalog.server.service.lance.backend.LanceAdvancedExecutionBackend;
import io.unitycatalog.server.service.lance.backend.LanceExecutionContext;
import io.unitycatalog.server.service.lance.backend.LanceExecutionResult;
import io.unitycatalog.server.utils.ServerProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Version metadata APIs expose worker-reported Lance commit history from UC. Creating versions is
 * rejected because versions originate from physical Lance writes, not catalog-only mutations.
 */
@ExceptionHandler(LanceExceptionHandler.class)
public class LanceRestVersionService {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final LanceTableResolver tableResolver;
  private final LanceVersionRepository versionRepository;
  private final LanceAuthorizationService authorizationService;
  private final LanceAdvancedExecutionBackend backend;
  private final LanceDataPlaneService dataPlaneService;
  private final ServerProperties serverProperties;

  public LanceRestVersionService(
      Repositories repositories,
      UnityCatalogAuthorizer authorizer,
      LanceAdvancedExecutionBackend backend,
      ServerProperties serverProperties) {
    this.tableResolver = new LanceTableResolver(repositories);
    this.versionRepository = repositories.getLanceVersionRepository();
    this.authorizationService = new LanceAuthorizationService(repositories, authorizer);
    this.backend = backend;
    this.serverProperties = serverProperties;
    this.dataPlaneService =
        new LanceDataPlaneService(
            backend,
            new LanceTableResolver(repositories),
            new LanceStorageOptionsService(
                backend.getClass().getName().endsWith(".LanceTestEchoExecutionBackend")),
            new LanceDataPlaneAuthorizer(repositories, authorizer, serverProperties),
            new LanceDataPlaneMetadataUpdater(
                repositories.getLanceTableRepository(), repositories.getLanceVersionRepository()),
            serverProperties.isLanceExecutionLegacyReadEnabled());
  }

  @Post("/v1/table/{id}/version/list")
  public HttpResponse listVersions(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      VersionListRequest request) {
    ResolvedLanceTable table = resolveNativeTable(id, delimiter.orElse(null), "version history");
    authorizationService.authorizeReadTable(table.assetDAO());

    Integer safeLimit = safeLimit(request == null ? null : request.pageSize());
    List<LanceVersionDAO> versions =
        versionRepository.listVersions(
            table.assetDAO().getId(),
            optionalLong(request == null ? null : request.startVersion()),
            optionalLong(request == null ? null : request.endVersion()),
            safeLimit == null ? Optional.empty() : Optional.of(safeLimit),
            optionalLong(request == null ? null : request.pageToken()));

    String nextPageToken = null;
    if (safeLimit != null && versions.size() > safeLimit) {
      nextPageToken = String.valueOf(versions.get(safeLimit - 1).getVersion());
      versions = versions.subList(0, safeLimit);
    }

    return HttpResponse.ofJson(
        new VersionListResponse(
            versions.stream().map(this::toVersionView).toList(), nextPageToken));
  }

  @Post("/v1/table/{id}/version/describe")
  public HttpResponse describeVersion(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      VersionDescribeRequest request) {
    ResolvedLanceTable table = resolveNativeTable(id, delimiter.orElse(null), "version metadata");
    authorizationService.authorizeReadTable(table.assetDAO());
    Long version = request == null ? null : request.version();
    if (version == null) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance version is required.");
    }
    LanceVersionDAO versionDAO =
        versionRepository
            .findVersion(table.assetDAO().getId(), version)
            .orElseThrow(
                () ->
                    new BaseException(ErrorCode.NOT_FOUND, "Lance version not found: " + version));
    return HttpResponse.ofJson(toVersionView(versionDAO));
  }

  @Post("/v1/table/{id}/version/create")
  public HttpResponse createVersion(@Param("id") String id, Object ignored) {
    throw new BaseException(
        ErrorCode.INVALID_ARGUMENT,
        "Lance versions are generated by table writes and cannot be created manually.");
  }

  @Post("/v1/table/batch-create-versions")
  public HttpResponse batchCreateVersions(Object ignored) {
    throw new BaseException(
        ErrorCode.INVALID_ARGUMENT,
        "Lance versions are generated by table writes and cannot be batch-created manually.");
  }

  @Post("/v1/table/{id}/version/delete")
  public HttpResponse deleteVersions(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      AggregatedHttpRequest request) {
    validateJsonRequest(request);
    Map<String, Object> payload = readJson(request.contentUtf8());

    LanceExecutionContext context =
        executionContext(request.headers(), serverProperties.getLanceExecutionRequestTimeoutMs());

    // delete_versions crosses into the data plane because it physically changes Lance manifests.
    // UC records only the worker response; any version tombstone/history policy belongs in sync
    // metadata handling, not in this REST adapter.
    LanceExecutionResult result = dataPlaneService.deleteVersions(id, delimiter, context, payload);
    return json(result.payload());
  }

  private void validateJsonRequest(AggregatedHttpRequest request) {
    if (request.contentType() == null
        || !request.contentType().toString().contains("application/json")) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Request must be JSON.");
    }
  }

  private Map<String, Object> readJson(String content) {
    if (content == null || content.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return OBJECT_MAPPER.readValue(content, MAP_TYPE);
    } catch (JsonProcessingException e) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Invalid JSON request body.", e);
    }
  }

  private HttpResponse json(Map<String, Object> payload) {
    try {
      return HttpResponse.ofJson(OBJECT_MAPPER.writeValueAsString(payload));
    } catch (JsonProcessingException e) {
      throw new BaseException(ErrorCode.INTERNAL, "Failed to serialize response.", e);
    }
  }

  private LanceExecutionContext executionContext(
      com.linecorp.armeria.common.RequestHeaders headers, long defaultDeadlineMs) {
    String idempotencyKey = headers.get("idempotency-key");
    String requestId = headers.get("x-request-id");
    if (requestId == null || requestId.isBlank()) {
      requestId = UUID.randomUUID().toString();
    }
    String deadline = headers.get("x-lance-deadline-ms");
    Long deadlineMs = null;
    if (deadline != null && !deadline.isBlank()) {
      try {
        deadlineMs = Long.parseLong(deadline);
      } catch (NumberFormatException ignored) {
        deadlineMs = defaultDeadlineMs;
      }
    }
    if (deadlineMs == null) {
      deadlineMs = defaultDeadlineMs;
    }
    return new LanceExecutionContext(
        requestId,
        LanceRequestContext.currentPrincipal(),
        authType(headers),
        deadlineMs,
        LanceRequestContext.currentContextHeaders(),
        idempotencyKey == null || idempotencyKey.isBlank() ? null : sha256(idempotencyKey));
  }

  private String authType(com.linecorp.armeria.common.RequestHeaders headers) {
    String auth = headers.get(com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION);
    if (auth != null && auth.startsWith("Bearer ")) {
      return "bearer";
    }
    String apiKey = headers.get("x-api-key");
    if (apiKey != null && !apiKey.isBlank()) {
      return "api_key";
    }
    return "none";
  }

  private String sha256(String value) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hexString = new StringBuilder();
      for (byte b : hash) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      return null;
    }
  }

  private ResolvedLanceTable resolveNativeTable(String id, String delimiter, String operation) {
    ResolvedLanceTable table = tableResolver.resolve(id, delimiter);
    if (table.legacyBridge()) {
      throw new BaseException(
          ErrorCode.UNIMPLEMENTED, "Legacy bridge Lance tables do not support " + operation + ".");
    }
    return table;
  }

  private VersionView toVersionView(LanceVersionDAO versionDAO) {
    return new VersionView(
        versionDAO.getVersion(),
        versionDAO.getOperation(),
        timestamp(versionDAO),
        versionDAO.getManifestPath(),
        versionDAO.getManifestSize(),
        versionDAO.getEtag(),
        parseJson(versionDAO.getMetadataJson(), "version metadata_json"),
        parseJson(versionDAO.getStatsJson(), "version stats_json"),
        versionDAO.getCreatedBy(),
        versionDAO.getCreatedAt() == null
            ? null
            : versionDAO.getCreatedAt().toInstant().toString());
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

  private String timestamp(LanceVersionDAO versionDAO) {
    return versionDAO.getTimestamp() == null
        ? null
        : versionDAO.getTimestamp().toInstant().toString();
  }

  private Integer safeLimit(Integer limit) {
    return limit != null && limit > 0 ? limit : null;
  }

  private Optional<Long> optionalLong(Long value) {
    return value == null ? Optional.empty() : Optional.of(value);
  }

  public record VersionListRequest(
      @JsonProperty("page_token") Long pageToken,
      @JsonProperty("page_size") Integer pageSize,
      @JsonProperty("start_version") Long startVersion,
      @JsonProperty("end_version") Long endVersion) {}

  public record VersionDescribeRequest(Long version) {}

  public record VersionListResponse(
      List<VersionView> versions, @JsonProperty("next_page_token") String nextPageToken) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record VersionView(
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
}
