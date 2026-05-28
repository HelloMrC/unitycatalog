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
import io.unitycatalog.server.persist.LanceIndexRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceIndexDAO;
import io.unitycatalog.server.service.lance.util.LanceHeaderUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Index metadata endpoints. Physical index build/drop remains a Lance worker responsibility; this
 * service exposes the UC catalog view and accepts worker-reported sync state.
 */
@ExceptionHandler(LanceExceptionHandler.class)
public class LanceRestIndexService {
  private static final Logger LOGGER = LoggerFactory.getLogger(LanceRestIndexService.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final LanceTableResolver tableResolver;
  private final LanceIndexRepository indexRepository;
  private final LanceAuthorizationService authorizationService;

  public LanceRestIndexService(Repositories repositories, UnityCatalogAuthorizer authorizer) {
    this.tableResolver = new LanceTableResolver(repositories);
    this.indexRepository = repositories.getLanceIndexRepository();
    this.authorizationService = new LanceAuthorizationService(repositories, authorizer);
  }

  @Post("/v1/table/{id}/index/list")
  public HttpResponse listIndices(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      IndexListRequest request) {
    ResolvedLanceTable table = resolveNativeTable(id, delimiter.orElse(null), "index metadata");
    authorizationService.authorizeReadTable(table.assetDAO());

    List<LanceIndexDAO> indices =
        indexRepository.listIndices(
            table.assetDAO().getId(),
            optionalString(request == null ? null : request.status()),
            optionalInt(request == null ? null : request.pageSize()));

    return HttpResponse.ofJson(
        new IndexListResponse(indices.stream().map(this::toIndexView).toList()));
  }

  @Post("/v1/table/{id}/index/describe")
  public HttpResponse describeIndex(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      IndexDescribeRequest request) {
    ResolvedLanceTable table = resolveNativeTable(id, delimiter.orElse(null), "index metadata");
    authorizationService.authorizeReadTable(table.assetDAO());

    String indexName =
        resolveIndexName(
            request == null ? null : request.indexName(), request == null ? null : request.name());
    LanceIndexDAO indexDAO =
        indexRepository
            .findIndex(table.assetDAO().getId(), indexName)
            .orElseThrow(
                () ->
                    new BaseException(ErrorCode.NOT_FOUND, "Lance index not found: " + indexName));
    return HttpResponse.ofJson(toIndexView(indexDAO));
  }

  @Post("/v1/table/{id}/metadata/sync/index")
  public HttpResponse syncIndex(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      SyncIndexRequest request) {
    ResolvedLanceTable table =
        resolveActiveNativeTable(id, delimiter.orElse(null), "sync index metadata");
    authorizationService.authorizeModifyTable(table.assetDAO());

    if (request == null || request.indexName() == null) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance index name is required.");
    }

    String createdBy =
        request.createdBy() == null || request.createdBy().isBlank()
            ? currentPrincipal(table)
            : request.createdBy();

    // syncIndex is the boundary between Worker-owned index files and UC-owned catalog metadata.
    // Upsert allows workers to retry completion callbacks without creating duplicate index rows.
    LanceIndexDAO indexDAO =
        indexRepository.upsertIndex(
            table.assetDAO().getId(),
            request.indexName(),
            request.indexType(),
            toJson(request.targetColumns(), "index target columns"),
            request.distanceType(),
            toJson(request.buildParams(), "index build params"),
            toJson(request.stats(), "index stats"),
            request.status() == null ? "READY" : request.status(),
            createdBy);

    auditSyncOperation(
        "syncIndex",
        table.assetDAO().getId(),
        request.indexName(),
        createdBy,
        LanceHeaderUtil.getIdempotencyKey());

    return HttpResponse.ofJson(toIndexView(indexDAO));
  }

  private ResolvedLanceTable resolveNativeTable(String id, String delimiter, String operation) {
    ResolvedLanceTable table = tableResolver.resolve(id, delimiter);
    if (table.legacyBridge()) {
      throw new BaseException(
          ErrorCode.UNIMPLEMENTED, "Legacy bridge Lance tables do not support " + operation + ".");
    }
    return table;
  }

  private ResolvedLanceTable resolveActiveNativeTable(
      String id, String delimiter, String operation) {
    ResolvedLanceTable table = resolveNativeTable(id, delimiter, operation);
    // A declared-only table has no guaranteed physical Lance dataset, so accepting index metadata
    // would make UC advertise an index that the worker may not be able to serve.
    if (table.tableRef().declaredOnly()) {
      throw new BaseException(
          ErrorCode.ABORTED, "Declared Lance table must be materialized before " + operation + ".");
    }
    return table;
  }

  private String resolveIndexName(String indexName, String name) {
    String resolved = indexName == null || indexName.isBlank() ? name : indexName;
    if (resolved == null || resolved.isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance index_name is required.");
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

  private void auditSyncOperation(
      String operation,
      Object resourceId,
      Object keyInfo,
      String principal,
      String idempotencyKey) {
    String idempotencyKeyHash = sha256Hash(idempotencyKey);
    LOGGER.info(
        "Lance sync API: operation={}, resourceId={}, key={}, principal={}, idempotencyKeyHash={}",
        operation,
        resourceId,
        keyInfo,
        principal,
        idempotencyKeyHash != null ? idempotencyKeyHash : "none");
  }

  private String sha256Hash(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hexString = new StringBuilder();
      for (byte b : hash) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (NoSuchAlgorithmException e) {
      LOGGER.warn("SHA-256 algorithm not available for idempotency key hashing");
      return null;
    }
  }

  private IndexView toIndexView(LanceIndexDAO indexDAO) {
    return new IndexView(
        indexDAO.getIndexName(),
        indexDAO.getIndexType(),
        parseJson(indexDAO.getTargetColumnsJson(), "index target_columns_json"),
        indexDAO.getDistanceType(),
        parseJson(indexDAO.getBuildParamsJson(), "index build_params_json"),
        parseJson(indexDAO.getStatsJson(), "index stats_json"),
        indexDAO.getStatus(),
        indexDAO.getCreatedAt() == null ? null : indexDAO.getCreatedAt().toInstant().toString(),
        indexDAO.getCreatedBy(),
        indexDAO.getUpdatedAt() == null ? null : indexDAO.getUpdatedAt().toInstant().toString(),
        indexDAO.getUpdatedBy());
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

  private Optional<String> optionalString(String value) {
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
  }

  private Optional<Integer> optionalInt(Integer value) {
    return value == null || value <= 0 ? Optional.empty() : Optional.of(value);
  }

  public record IndexListRequest(String status, @JsonProperty("page_size") Integer pageSize) {}

  public record IndexDescribeRequest(@JsonProperty("index_name") String indexName, String name) {}

  public record SyncIndexRequest(
      @JsonProperty("index_name") String indexName,
      @JsonProperty("index_type") String indexType,
      @JsonProperty("target_columns") Object targetColumns,
      @JsonProperty("distance_type") String distanceType,
      @JsonProperty("build_params") Object buildParams,
      Object stats,
      String status,
      @JsonProperty("created_by") String createdBy) {}

  public record IndexListResponse(List<IndexView> indices) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record IndexView(
      @JsonProperty("index_name") String indexName,
      @JsonProperty("index_type") String indexType,
      @JsonProperty("target_columns") Object targetColumns,
      @JsonProperty("distance_type") String distanceType,
      @JsonProperty("build_params") Object buildParams,
      Object stats,
      String status,
      @JsonProperty("created_at") String createdAt,
      @JsonProperty("created_by") String createdBy,
      @JsonProperty("updated_at") String updatedAt,
      @JsonProperty("updated_by") String updatedBy) {}
}
