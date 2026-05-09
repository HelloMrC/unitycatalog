package io.unitycatalog.server.service.lance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

class LanceRealLanceDbWorkerProcessFixture implements AutoCloseable {
  static final String HEALTH_PATH = "/internal/lance/v1/health";

  private static final String SCRIPT_RESOURCE = "lance/real_lancedb_worker.py";
  private static final long START_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);

  private final StringBuilder output = new StringBuilder();
  private Process process;
  private String baseUrl;

  static DependencyCheck checkDependencies() {
    return runAndCapture("--check");
  }

  static byte[] sampleArrowStream() {
    DependencyCheck result = runAndCapture("--sample-arrow");
    if (!result.available()) {
      throw new IllegalStateException(result.message());
    }
    return Base64.getDecoder().decode(result.message().replaceAll("\\s", ""));
  }

  void start(Path root) {
    try {
      ProcessBuilder builder =
          new ProcessBuilder(
              pythonCommand(),
              scriptPath().toString(),
              "--port",
              "0",
              "--root",
              root.toAbsolutePath().normalize().toString());
      applyPythonPath(builder);
      builder.redirectErrorStream(true);
      process = builder.start();
      waitUntilReady();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to start real LanceDB worker process.", e);
    }
  }

  String baseUrl() {
    return baseUrl;
  }

  @Override
  public void close() {
    if (process != null) {
      process.destroy();
      try {
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroyForcibly();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
      }
      process = null;
    }
  }

  private void waitUntilReady() {
    long deadline = System.nanoTime() + START_TIMEOUT_NANOS;
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      while (System.nanoTime() < deadline) {
        if (reader.ready()) {
          String line = reader.readLine();
          output.append(line).append('\n');
          if (line.startsWith("READY ")) {
            baseUrl = "http://127.0.0.1:" + line.substring("READY ".length()).trim();
            return;
          }
        }
        if (!process.isAlive()) {
          break;
        }
        Thread.sleep(100);
      }
    } catch (IOException e) {
      output.append(e.getMessage()).append('\n');
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    close();
    throw new IllegalStateException("Real LanceDB worker did not become ready. Output:\n" + output);
  }

  private static DependencyCheck runAndCapture(String argument) {
    try {
      ProcessBuilder builder =
          new ProcessBuilder(pythonCommand(), scriptPath().toString(), argument);
      applyPythonPath(builder);
      builder.redirectErrorStream(true);
      Process process = builder.start();
      boolean exited = process.waitFor(10, TimeUnit.SECONDS);
      String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (!exited) {
        process.destroyForcibly();
        return new DependencyCheck(false, "Timed out checking real LanceDB worker dependencies.");
      }
      return new DependencyCheck(process.exitValue() == 0, output);
    } catch (IOException e) {
      return new DependencyCheck(false, e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new DependencyCheck(false, e.getMessage());
    }
  }

  private static void applyPythonPath(ProcessBuilder builder) {
    String pythonPath = System.getenv("LANCE_REAL_WORKER_PYTHONPATH");
    if (pythonPath != null && !pythonPath.isBlank()) {
      builder.environment().put("PYTHONPATH", pythonPath);
    }
  }

  private static String pythonCommand() {
    String property = System.getProperty("lance.real.worker.python");
    if (property != null && !property.isBlank()) {
      return property;
    }
    String environment = System.getenv("LANCE_REAL_WORKER_PYTHON");
    return environment == null || environment.isBlank() ? "python3" : environment;
  }

  private static Path scriptPath() {
    URL resource =
        LanceRealLanceDbWorkerProcessFixture.class.getClassLoader().getResource(SCRIPT_RESOURCE);
    if (resource == null) {
      throw new IllegalStateException("Missing test resource " + SCRIPT_RESOURCE + ".");
    }
    try {
      return Path.of(resource.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Invalid worker script resource path.", e);
    }
  }

  record DependencyCheck(boolean available, String message) {}
}
