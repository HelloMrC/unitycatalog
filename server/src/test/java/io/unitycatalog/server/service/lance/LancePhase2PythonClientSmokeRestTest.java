package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2PythonClientSmokeRestTest extends BaseLancePhase2RestTest {
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
  @DisplayName("P2-CLIENT-002 Python client connect query count and insert smoke")
  void pythonClientConnectQueryCountAndInsertSmoke() throws Exception {
    createActiveTableFixture();

    JsonNode result =
        runPythonClientSmoke(serverConfig.getServerUrl() + LANCE_API_PREFIX, P2_ACTIVE_TABLE_ID);

    assertThat(result.path("connectOk").asBoolean()).isTrue();
    assertThat(result.path("insertOk").asBoolean()).isTrue();
    assertThat(result.path("queryOk").asBoolean()).isTrue();
    assertThat(result.path("count").asLong()).isEqualTo(2L);
    assertThat(result.path("queryRows").asLong()).isEqualTo(2L);
    assertThat(result.path("columns").toString()).contains("id", "text");
  }

  private JsonNode runPythonClientSmoke(String baseUrl, String tableId)
      throws IOException, InterruptedException {
    ProcessBuilder builder =
        new ProcessBuilder(
            LanceRealLanceDbWorkerProcessFixture.pythonCommand(),
            LanceRealLanceDbWorkerProcessFixture.scriptPath("lance/python_client_smoke.py")
                .toString(),
            "--base-url",
            baseUrl,
            "--table-id",
            tableId);
    LanceRealLanceDbWorkerProcessFixture.applyPythonPath(builder);
    builder.redirectErrorStream(true);
    Process process = builder.start();
    boolean exited = process.waitFor(30, TimeUnit.SECONDS);
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!exited) {
      process.destroyForcibly();
    }
    assertThat(exited).as("Python client smoke timed out. Output:\n" + output).isTrue();
    assertThat(process.exitValue()).as(output).isEqualTo(0);
    return objectMapper.readTree(output);
  }
}
