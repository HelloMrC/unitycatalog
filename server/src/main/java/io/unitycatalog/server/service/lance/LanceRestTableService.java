package io.unitycatalog.server.service.lance;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import io.unitycatalog.server.persist.Repositories;
import java.util.Optional;

@ExceptionHandler(LanceExceptionHandler.class)
public class LanceRestTableService {
  private final LanceMetadataService metadataService;

  public LanceRestTableService(Repositories repositories) {
    this.metadataService = new LanceMetadataService(repositories);
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
            id, delimiter.orElse(null), location, vendCredentials.orElse(false)));
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
            id, delimiter.orElse(null), location, vendCredentials.orElse(false), false));
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
            id, delimiter.orElse(null), location, vendCredentials.orElse(false), true));
  }

  @Post("/v1/table/{id}/describe")
  public HttpResponse describeTable(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      @Param("vend_credentials") Optional<Boolean> vendCredentials,
      Object ignored) {
    return HttpResponse.ofJson(
        metadataService.describeTable(id, delimiter.orElse(null), vendCredentials.orElse(false)));
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
      Object ignored) {
    String effectiveMode = mode.orElse(null);
    return HttpResponse.ofJson(
        metadataService.dropTable(id, delimiter.orElse(null), effectiveMode));
  }

  @Post("/v1/table/{id}/deregister")
  public HttpResponse deregisterTable(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      @Param("delete_physical_data") Optional<Boolean> deletePhysicalData,
      Object ignored) {
    boolean effectiveDelete = deletePhysicalData.orElse(false);
    return HttpResponse.ofJson(
        metadataService.deregisterTable(id, delimiter.orElse(null), effectiveDelete));
  }

  public record TableCreateRequest(String location) {}
}
