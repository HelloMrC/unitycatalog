package io.unitycatalog.server.service.lance;

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
public class LanceRestNamespaceService {
  private final LanceMetadataService metadataService;

  public LanceRestNamespaceService(Repositories repositories, UnityCatalogAuthorizer authorizer) {
    this.metadataService = new LanceMetadataService(repositories, authorizer);
  }

  @Post("/v1/namespace/{id}/create")
  public HttpResponse createNamespace(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      NamespaceCreateRequest request) {
    Map<String, String> properties = request == null ? Map.of() : request.properties();
    return HttpResponse.ofJson(
        metadataService.createNamespace(id, delimiter.orElse(null), properties));
  }

  @Post("/v1/namespace/{id}/describe")
  public HttpResponse describeNamespace(
      @Param("id") String id, @Param("delimiter") Optional<String> delimiter, Object ignored) {
    return HttpResponse.ofJson(metadataService.describeNamespace(id, delimiter.orElse(null)));
  }

  @Get("/v1/namespace/{id}/describe")
  public HttpResponse describeNamespaceMethodNotAllowed(@Param("id") String id) {
    return methodNotAllowed();
  }

  @Post("/v1/namespace/{id}/exists")
  public HttpResponse namespaceExists(
      @Param("id") String id, @Param("delimiter") Optional<String> delimiter, Object ignored) {
    return HttpResponse.ofJson(metadataService.namespaceExists(id, delimiter.orElse(null)));
  }

  @Post("/v1/namespace/{id}/drop")
  public HttpResponse dropNamespace(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      @Param("mode") Optional<String> mode,
      NamespaceDropRequest request) {
    String effectiveMode = mode.orElse(request == null ? null : request.mode());
    return HttpResponse.ofJson(
        metadataService.dropNamespace(id, delimiter.orElse(null), effectiveMode));
  }

  @Get("/v1/namespace/{id}/list")
  public HttpResponse listNamespaces(
      @Param("id") String id,
      @Param("delimiter") Optional<String> delimiter,
      @Param("limit") Optional<Integer> limit,
      @Param("pageToken") Optional<String> pageToken) {
    return HttpResponse.ofJson(
        metadataService.listNamespaces(
            id, delimiter.orElse(null), limit.orElse(null), pageToken.orElse(null)));
  }

  public record NamespaceCreateRequest(Map<String, String> properties) {}

  public record NamespaceDropRequest(String mode) {}

  private static HttpResponse methodNotAllowed() {
    return HttpResponse.ofJson(
        HttpStatus.METHOD_NOT_ALLOWED,
        Map.of(
            "type", "method_not_allowed",
            "message", "Method not allowed. Use POST.",
            "code", HttpStatus.METHOD_NOT_ALLOWED.code()));
  }
}
