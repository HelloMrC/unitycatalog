package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2JavaRustClientSmokeRestTest extends BaseLancePhase2RestTest {
  private LanceRealLanceDbWorkerProcessFixture worker;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    LanceRealLanceDbWorkerProcessFixture.DependencyCheck check =
        LanceRealLanceDbWorkerProcessFixture.checkDependencies();
    assumeTrue(
        check.available(),
        () -> "real LanceDB worker dependencies unavailable: " + check.message());
    worker = new LanceRealLanceDbWorkerProcessFixture();
    worker.start(testDirectoryRoot.resolve("real-lancedb-worker"));
    serverProperties.setProperty(Property.LANCE_EXECUTION_BACKEND_TYPE.getKey(), "worker-http");
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_WORKER_BASE_URL.getKey(), worker.baseUrl());
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_WORKER_HEALTH_PATH.getKey(),
        LanceRealLanceDbWorkerProcessFixture.HEALTH_PATH);
  }

  @AfterEach
  @Override
  public void tearDown() {
    try {
      super.tearDown();
    } finally {
      if (worker != null) {
        worker.close();
      }
    }
  }

  @Test
  @DisplayName("P2-CLIENT-004 Java JDK HTTP client query count stats smoke")
  void javaJdkHttpClientQueryCountStatsSmoke() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            LanceRealLanceDbWorkerProcessFixture.sampleArrowStream()));

    JdkLanceClient client = new JdkLanceClient(serverConfig.getServerUrl() + LANCE_API_PREFIX);

    byte[] query = client.postBytes("/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", "{}");
    assertThat(query).startsWith("ARROW1".getBytes(StandardCharsets.UTF_8));

    JsonNode count = client.postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows", "{}");
    assertThat(count.asLong()).isEqualTo(2L);

    JsonNode stats = client.postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}");
    assertThat(stats.path("numRows").asLong()).isEqualTo(2L);
    assertThat(stats.path("realLanceDbWorker").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P2-CLIENT-005 Rust std HTTP client query smoke")
  void rustStdHttpClientQuerySmoke() throws Exception {
    assumeTrue(commandAvailable("rustc", "--version"), "rustc is unavailable.");
    createActiveTableFixture();
    assertSuccess(
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            LanceRealLanceDbWorkerProcessFixture.sampleArrowStream()));

    JsonNode result =
        runRustClientSmoke(serverConfig.getServerUrl() + LANCE_API_PREFIX, P2_ACTIVE_TABLE_ID);

    assertThat(result.path("queryOk").asBoolean()).isTrue();
    assertThat(result.path("status").asInt()).isEqualTo(200);
    assertThat(result.path("contentType").asText()).contains("application/vnd.apache.arrow");
    assertThat(result.path("bodyPrefix").asText()).isEqualTo("ARROW1");
  }

  private JsonNode runRustClientSmoke(String baseUrl, String tableId)
      throws IOException, InterruptedException {
    Path source = LanceRealLanceDbWorkerProcessFixture.scriptPath("lance/rust_client_smoke.rs");
    Path binary = testDirectoryRoot.resolve("rust-client-smoke");
    runProcess(
        List.of("rustc", "--edition=2021", source.toString(), "-o", binary.toString()),
        "Rust client smoke compilation failed");
    String output =
        runProcess(
            List.of(binary.toString(), "--base-url", baseUrl, "--table-id", tableId),
            "Rust client smoke failed");
    return objectMapper.readTree(output);
  }

  private String runProcess(List<String> command, String failureMessage)
      throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    Process process = builder.start();
    boolean exited = process.waitFor(30, TimeUnit.SECONDS);
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!exited) {
      process.destroyForcibly();
    }
    assertThat(exited).as(failureMessage + " timed out. Output:\n" + output).isTrue();
    assertThat(process.exitValue()).as(failureMessage + ". Output:\n" + output).isEqualTo(0);
    return output;
  }

  private boolean commandAvailable(String... command) {
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      boolean exited = process.waitFor(10, TimeUnit.SECONDS);
      if (!exited) {
        process.destroyForcibly();
        return false;
      }
      return process.exitValue() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private class JdkLanceClient {
    private final String baseUrl;
    private final HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private JdkLanceClient(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    private JsonNode postJson(String path, String body) throws Exception {
      HttpResponse<String> response =
          client.send(
              request(path, body, "application/json", "application/json"),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
      return objectMapper.readTree(response.body());
    }

    private byte[] postBytes(String path, String body) throws Exception {
      HttpResponse<byte[]> response =
          client.send(
              request(path, body, "application/json", ARROW_FILE.toString()),
              HttpResponse.BodyHandlers.ofByteArray());
      assertThat(response.statusCode())
          .as(new String(response.body(), StandardCharsets.UTF_8))
          .isBetween(200, 299);
      assertThat(response.headers().firstValue("content-type").orElse(""))
          .contains("application/vnd.apache.arrow");
      return response.body();
    }

    private HttpRequest request(String path, String body, String contentType, String accept) {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(baseUrl + path))
              .timeout(Duration.ofSeconds(30))
              .header("content-type", contentType)
              .header("accept", accept)
              .header("x-request-id", "java-client-smoke")
              .header("x-lance-tenant-id", "java-smoke");
      return builder
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
          .build();
    }
  }
}
