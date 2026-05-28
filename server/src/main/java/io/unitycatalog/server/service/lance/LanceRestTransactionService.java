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
import io.unitycatalog.server.persist.LanceTransactionRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceTransactionDAO;
import io.unitycatalog.server.service.lance.util.LanceHeaderUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transaction metadata endpoints. Transaction execution is delegated to Lance workers; UC records
 * transaction state for discovery, audit, and retry/reconciliation workflows.
 */
@ExceptionHandler(LanceExceptionHandler.class)
public class LanceRestTransactionService {
  private static final Logger LOGGER = LoggerFactory.getLogger(LanceRestTransactionService.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final LanceTableResolver tableResolver;
  private final LanceTransactionRepository transactionRepository;
  private final LanceAuthorizationService authorizationService;

  public LanceRestTransactionService(Repositories repositories, UnityCatalogAuthorizer authorizer) {
    this.tableResolver = new LanceTableResolver(repositories);
    this.transactionRepository = repositories.getLanceTransactionRepository();
    this.authorizationService = new LanceAuthorizationService(repositories, authorizer);
  }

  @Post("/v1/table/{id}/transaction/list")
  public HttpResponse listTransactions(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      TransactionListRequest request) {
    ResolvedLanceTable table =
        resolveNativeTable(id, delimiter.orElse(null), "transaction metadata");
    authorizationService.authorizeReadTable(table.assetDAO());

    List<LanceTransactionDAO> transactions =
        transactionRepository.listTransactions(
            table.assetDAO().getId(),
            optionalString(request == null ? null : request.status()),
            optionalInt(request == null ? null : request.pageSize()));

    return HttpResponse.ofJson(
        new TransactionListResponse(transactions.stream().map(this::toTransactionView).toList()));
  }

  @Post("/v1/table/{id}/transaction/describe")
  public HttpResponse describeTransaction(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      TransactionDescribeRequest request) {
    ResolvedLanceTable table =
        resolveNativeTable(id, delimiter.orElse(null), "transaction metadata");
    authorizationService.authorizeReadTable(table.assetDAO());

    String transactionKey =
        resolveTransactionKey(
            request == null ? null : request.transactionKey(),
            request == null ? null : request.key());
    LanceTransactionDAO dao =
        transactionRepository
            .findByKey(transactionKey)
            .orElseThrow(
                () ->
                    new BaseException(
                        ErrorCode.NOT_FOUND, "Lance transaction not found: " + transactionKey));
    return HttpResponse.ofJson(toTransactionView(dao));
  }

  @Post("/v1/table/{id}/metadata/sync/transaction")
  public HttpResponse syncTransaction(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      SyncTransactionRequest request) {
    ResolvedLanceTable table =
        resolveActiveNativeTable(id, delimiter.orElse(null), "sync transaction metadata");
    authorizationService.authorizeModifyTable(table.assetDAO());

    if (request == null || request.transactionKey() == null || request.transactionKey().isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance transaction_key is required.");
    }

    String createdBy =
        request.createdBy() == null || request.createdBy().isBlank()
            ? currentPrincipal(table)
            : request.createdBy();

    // Worker callbacks use transaction_key as the idempotent identity. Keeping this as an upsert
    // lets a RUNNING transaction later converge to SUCCEEDED/FAILED without a separate update API.
    LanceTransactionDAO dao =
        transactionRepository.upsertTransaction(
            table.assetDAO().getId(),
            request.transactionKey(),
            request.status(),
            toJson(request.actions(), "transaction actions"),
            toJson(request.commitMetadata(), "transaction commit metadata"),
            createdBy);

    auditSyncOperation(
        "syncTransaction",
        table.assetDAO().getId(),
        request.transactionKey(),
        createdBy,
        LanceHeaderUtil.getIdempotencyKey());

    return HttpResponse.ofJson(toTransactionView(dao));
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
    // Transaction metadata is meaningful only after a native table has been materialized. Declared
    // placeholders do not yet have a stable physical dataset for a worker transaction to target.
    if (table.tableRef().declaredOnly()) {
      throw new BaseException(
          ErrorCode.ABORTED, "Declared Lance table must be materialized before " + operation + ".");
    }
    return table;
  }

  private String resolveTransactionKey(String transactionKey, String key) {
    String resolved = transactionKey == null || transactionKey.isBlank() ? key : transactionKey;
    if (resolved == null || resolved.isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Lance transaction_key is required.");
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

  private TransactionView toTransactionView(LanceTransactionDAO dao) {
    return new TransactionView(
        dao.getTransactionKey(),
        dao.getStatus(),
        parseJson(dao.getActionsJson(), "transaction actions_json"),
        parseJson(dao.getCommitMetadataJson(), "transaction commit_metadata_json"),
        dao.getCreatedAt() == null ? null : dao.getCreatedAt().toInstant().toString(),
        dao.getCreatedBy(),
        dao.getUpdatedAt() == null ? null : dao.getUpdatedAt().toInstant().toString(),
        dao.getUpdatedBy());
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

  public record TransactionListRequest(
      String status, @JsonProperty("page_size") Integer pageSize) {}

  public record TransactionDescribeRequest(
      @JsonProperty("transaction_key") String transactionKey, String key) {}

  public record SyncTransactionRequest(
      @JsonProperty("transaction_key") String transactionKey,
      String status,
      Object actions,
      @JsonProperty("commit_metadata") Object commitMetadata,
      @JsonProperty("created_by") String createdBy) {}

  public record TransactionListResponse(List<TransactionView> transactions) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TransactionView(
      @JsonProperty("transaction_key") String transactionKey,
      String status,
      Object actions,
      @JsonProperty("commit_metadata") Object commitMetadata,
      @JsonProperty("created_at") String createdAt,
      @JsonProperty("created_by") String createdBy,
      @JsonProperty("updated_at") String updatedAt,
      @JsonProperty("updated_by") String updatedBy) {}
}
