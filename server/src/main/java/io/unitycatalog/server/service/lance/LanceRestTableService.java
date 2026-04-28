package io.unitycatalog.server.service.lance;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import io.unitycatalog.server.auth.UnityCatalogAuthorizer;
import io.unitycatalog.server.persist.Repositories;
import java.util.Map;
import java.util.Optional;

@ExceptionHandler(LanceExceptionHandler.class)
public class LanceRestTableService {
  private final LanceMetadataService metadataService;

  public LanceRestTableService(Repositories repositories, UnityCatalogAuthorizer authorizer) {
    this.metadataService = new LanceMetadataService(repositories, authorizer);
  }

  @Get("/v1/namespace/{id}/table/list")
  public HttpResponse listTables(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      @Param("include_declared") Optional<Boolean> includeDeclared,
      @Param("limit") Optional<Integer> limit,
      @Param("pageToken") Optional<String> pageToken) {
    return HttpResponse.ofJson(
        metadataService.listTables(
            id,
            delimiter.orElse(null),
            includeDeclared.orElse(false),
            limit.orElse(null),
            pageToken.orElse(null)));
  }

  @Post("/v1/table/{id}/register")
  public HttpResponse registerTable(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      @Param("vend_credentials") Optional<Boolean> vendCredentials,
      TableCreateRequest request) {
    String location = request == null ? null : request.location();
    return HttpResponse.ofJson(
        metadataService.registerTable(
            id,
            delimiter.orElse(null),
            location,
            effectiveVendCredentials(vendCredentials, request),
            request == null ? Map.of() : request.storageOptionsTemplate(),
            request == null ? Map.of() : request.properties()));
  }

  @Post("/v1/table/{id}/declare")
  public HttpResponse declareTable(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      @Param("vend_credentials") Optional<Boolean> vendCredentials,
      TableCreateRequest request) {
    String location = request == null ? null : request.location();
    return HttpResponse.ofJson(
        metadataService.declareTable(
            id,
            delimiter.orElse(null),
            location,
            effectiveVendCredentials(vendCredentials, request),
            false,
            request == null ? Map.of() : request.storageOptionsTemplate(),
            request == null ? Map.of() : request.properties()));
  }

  @Post("/v1/table/{id}/create-empty")
  public HttpResponse createEmptyTable(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      @Param("vend_credentials") Optional<Boolean> vendCredentials,
      TableCreateRequest request) {
    String location = request == null ? null : request.location();
    return HttpResponse.ofJson(
        metadataService.declareTable(
            id,
            delimiter.orElse(null),
            location,
            effectiveVendCredentials(vendCredentials, request),
            true,
            request == null ? Map.of() : request.storageOptionsTemplate(),
            request == null ? Map.of() : request.properties()));
  }

  @Post("/v1/table/{id}/describe")
  public HttpResponse describeTable(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      @Param("vend_credentials") Optional<Boolean> vendCredentials,
      TableDescribeRequest request) {
    return HttpResponse.ofJson(
        metadataService.describeTable(
            id, delimiter.orElse(null), effectiveVendCredentials(vendCredentials, request)));
  }

  @Get("/v1/table/{id}/describe")
  public HttpResponse describeTableMethodNotAllowed(@Param("id") String id) {
    return methodNotAllowed();
  }

  @Post("/v1/table/{id}/exists")
  public HttpResponse tableExists(
      @Param("id") String id, @Param("delimiter") Optional<String> delimiter, Object ignored) {
    return HttpResponse.ofJson(metadataService.tableExists(id, delimiter.orElse(null)));
  }

  @Post("/v1/table/{id}/drop")
  public HttpResponse dropTable(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      @Param("mode") Optional<String> mode,
      TableDropRequest request) {
    String effectiveMode = mode.orElse(request == null ? null : request.mode());
    return HttpResponse.ofJson(
        metadataService.dropTable(id, delimiter.orElse(null), effectiveMode));
  }

  @Post("/v1/table/{id}/deregister")
  public HttpResponse deregisterTable(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      @Param("delete_physical_data") Optional<Boolean> deletePhysicalData,
      TableDeregisterRequest request) {
    boolean effectiveDelete =
        deletePhysicalData.orElse(
            request != null && Boolean.TRUE.equals(request.deletePhysicalData()));
    return HttpResponse.ofJson(
        metadataService.deregisterTable(id, delimiter.orElse(null), effectiveDelete));
  }

  private boolean effectiveVendCredentials(
      Optional<Boolean> queryVendCredentials, TableCreateRequest request) {
    return queryVendCredentials.orElse(
        request != null && Boolean.TRUE.equals(request.vendCredentials()));
  }

  private boolean effectiveVendCredentials(
      Optional<Boolean> queryVendCredentials, TableDescribeRequest request) {
    return queryVendCredentials.orElse(
        request != null && Boolean.TRUE.equals(request.vendCredentials()));
  }

  public record TableCreateRequest(
      String location,
      @JsonProperty("vend_credentials") Boolean vendCredentials,
      @JsonProperty("storage_options_template") Map<String, String> storageOptionsTemplate,
      Map<String, String> properties) {
    public Map<String, String> storageOptionsTemplate() {
      return storageOptionsTemplate == null ? Map.of() : storageOptionsTemplate;
    }

    public Map<String, String> properties() {
      return properties == null ? Map.of() : properties;
    }
  }

  public record TableDescribeRequest(
      @JsonProperty("vend_credentials") Boolean vendCredentials) {}

  public record TableDropRequest(String mode) {}

  public record TableDeregisterRequest(
      @JsonProperty("delete_physical_data") Boolean deletePhysicalData) {}

  private static HttpResponse methodNotAllowed() {
    return HttpResponse.ofJson(
        HttpStatus.METHOD_NOT_ALLOWED,
        Map.of(
            "type", "method_not_allowed",
            "message", "Method not allowed. Use POST.",
            "code", HttpStatus.METHOD_NOT_ALLOWED.code()));
  }
}
