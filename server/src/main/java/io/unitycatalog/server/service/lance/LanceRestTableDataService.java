package io.unitycatalog.server.service.lance;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import io.unitycatalog.server.service.lance.backend.LanceExecutionBackend;
import io.unitycatalog.server.service.lance.backend.LanceExecutionCommand;
import java.util.Map;
import java.util.Optional;

@ExceptionHandler(LanceExceptionHandler.class)
public class LanceRestTableDataService {
  private final LanceExecutionBackend backend;

  public LanceRestTableDataService(LanceExecutionBackend backend) {
    this.backend = backend;
  }

  @Post("/v1/table/{id}/query")
  public HttpResponse queryTable(
      @Param("id") String id, @Param("delimiter") Optional<String> delimiter, HttpRequest request) {
    return HttpResponse.ofJson(backend.query(command("query", id, delimiter)));
  }

  @Post("/v1/table/{id}/count_rows")
  public HttpResponse countRows(
      @Param("id") String id, @Param("delimiter") Optional<String> delimiter, HttpRequest request) {
    return HttpResponse.ofJson(backend.countRows(command("count_rows", id, delimiter)));
  }

  @Post("/v1/table/{id}/stats")
  public HttpResponse stats(
      @Param("id") String id, @Param("delimiter") Optional<String> delimiter, HttpRequest request) {
    return HttpResponse.ofJson(backend.stats(command("stats", id, delimiter)));
  }

  @Post("/v1/table/{id}/insert")
  public HttpResponse insert(
      @Param("id") String id, @Param("delimiter") Optional<String> delimiter, HttpRequest request) {
    return HttpResponse.ofJson(backend.insert(command("insert", id, delimiter)));
  }

  @Post("/v1/table/{id}/merge_insert")
  public HttpResponse mergeInsert(
      @Param("id") String id, @Param("delimiter") Optional<String> delimiter, HttpRequest request) {
    return HttpResponse.ofJson(backend.mergeInsert(command("merge_insert", id, delimiter)));
  }

  @Post("/v1/table/{id}/update")
  public HttpResponse update(
      @Param("id") String id, @Param("delimiter") Optional<String> delimiter, HttpRequest request) {
    return HttpResponse.ofJson(backend.update(command("update", id, delimiter)));
  }

  @Post("/v1/table/{id}/delete")
  public HttpResponse delete(
      @Param("id") String id, @Param("delimiter") Optional<String> delimiter, HttpRequest request) {
    return HttpResponse.ofJson(backend.delete(command("delete", id, delimiter)));
  }

  @Post("/v1/table/{id}/explain_plan")
  public HttpResponse explainPlan(
      @Param("id") String id, @Param("delimiter") Optional<String> delimiter, HttpRequest request) {
    return HttpResponse.ofJson(backend.explainPlan(command("explain_plan", id, delimiter)));
  }

  @Post("/v1/table/{id}/analyze_plan")
  public HttpResponse analyzePlan(
      @Param("id") String id, @Param("delimiter") Optional<String> delimiter, HttpRequest request) {
    return HttpResponse.ofJson(backend.analyzePlan(command("analyze_plan", id, delimiter)));
  }

  @Post("/v1/table/{id}/create")
  public HttpResponse create(
      @Param("id") String id, @Param("delimiter") Optional<String> delimiter, HttpRequest request) {
    return HttpResponse.ofJson(backend.create(command("create", id, delimiter)));
  }

  private LanceExecutionCommand command(String operation, String id, Optional<String> delimiter) {
    return new LanceExecutionCommand(operation, id, delimiter.orElse(null), Map.of());
  }
}
