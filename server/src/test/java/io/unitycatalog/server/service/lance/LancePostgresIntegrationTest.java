package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import io.unitycatalog.server.base.BaseServerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL integration tests for Lance metadata tables.
 *
 * <p>Prerequisites: - PostgreSQL Docker container running at localhost:5432 - Database "ucdb" with
 * user "uc_default_user" and password "uc_default_password"
 *
 * <p>Run with: ./build/sbt "server/testOnly LancePostgresIntegrationTest"
 */
@Tag("lance-postgres")
class LancePostgresIntegrationTest extends BaseServerTest {

  private static final String LANCE_API_PREFIX = "/api/2.1/unity-catalog/lance";

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty("postgresql.test.enabled", "true");
    serverProperties.setProperty(
        "postgresql.connection.url", "jdbc:postgresql://localhost:5432/ucdb");
    serverProperties.setProperty("postgresql.connection.username", "uc_default_user");
    serverProperties.setProperty("postgresql.connection.password", "uc_default_password");
  }

  private AggregatedHttpResponse postJson(String path, String body) {
    HttpRequest request = HttpRequest.of(HttpMethod.POST, path, MediaType.JSON, body);
    return WebClient.of(serverConfig.getServerUrl()).execute(request).aggregate().join();
  }

  @Test
  @DisplayName("PostgreSQL connection established")
  void postgresConnectionEstablished() {
    assertThat(hibernateConfigurator.getSessionFactory()).isNotNull();
    assertThat(hibernateConfigurator.getSessionFactory().isOpen()).isTrue();
    System.out.println("PostgreSQL connection verified successfully");
  }

  @Test
  @DisplayName("PostgreSQL Lance namespace DDL works")
  void postgresLanceNamespaceDDLWorks() {
    String namespaceId = "pg_test_ns";
    AggregatedHttpResponse response =
        postJson(
            LANCE_API_PREFIX + "/v1/namespace/" + namespaceId + "/create",
            "{\"properties\":{\"owner\":\"test\"}}");

    assertThat(response.status().code()).isIn(200, 201);
    System.out.println("PostgreSQL namespace creation verified");

    postJson(LANCE_API_PREFIX + "/v1/namespace/" + namespaceId + "/drop", "{}");
  }

  @Test
  @DisplayName("PostgreSQL Lance table DDL works")
  void postgresLanceTableDDLWorks() {
    String namespaceId = "pg_test_ns2";
    postJson(
        LANCE_API_PREFIX + "/v1/namespace/" + namespaceId + "/create",
        "{\"properties\":{\"owner\":\"test\"}}");

    String tableId = namespaceId + "$pg_test_table";
    AggregatedHttpResponse response =
        postJson(
            LANCE_API_PREFIX + "/v1/table/" + tableId + "/register",
            "{\"location\":\"file:///tmp/pg_test.lance\","
                + "\"properties\":{\"table_type\":\"lance\"}}");

    assertThat(response.status().code()).isIn(200, 201);
    System.out.println("PostgreSQL table creation verified");

    postJson(
        LANCE_API_PREFIX + "/v1/table/" + tableId + "/deregister",
        "{\"delete_physical_data\":false}");
    postJson(LANCE_API_PREFIX + "/v1/namespace/" + namespaceId + "/drop", "{}");
  }
}
