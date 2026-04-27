package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import io.unitycatalog.client.model.ColumnInfo;
import io.unitycatalog.client.model.ColumnTypeName;
import io.unitycatalog.client.model.CreateCatalog;
import io.unitycatalog.client.model.CreateSchema;
import io.unitycatalog.client.model.CreateTable;
import io.unitycatalog.client.model.DataSourceFormat;
import io.unitycatalog.client.model.TableInfo;
import io.unitycatalog.client.model.TableType;
import io.unitycatalog.server.base.BaseServerTest;
import io.unitycatalog.server.base.catalog.CatalogOperations;
import io.unitycatalog.server.base.schema.SchemaOperations;
import io.unitycatalog.server.base.table.TableOperations;
import io.unitycatalog.server.sdk.catalog.SdkCatalogOperations;
import io.unitycatalog.server.sdk.schema.SdkSchemaOperations;
import io.unitycatalog.server.sdk.tables.SdkTableOperations;
import io.unitycatalog.server.utils.TestUtils;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;

abstract class BaseLancePhase1RestTest extends BaseServerTest {
  protected static final String LANCE_API_PREFIX = "/api/2.1/unity-catalog/lance";
  protected static final String ROOT_NAMESPACE = "prod";
  protected static final String CHILD_NAMESPACE = "prod$team_a";
  protected static final String TABLE_ID = "prod$team_a$embeddings";
  protected static final String DECLARED_TABLE_ID = "prod$team_a$declared_only";
  protected static final String TABLE_LOCATION = "file:///tmp/uc-lance/embeddings.lance";
  protected static final String DECLARED_TABLE_LOCATION =
      "file:///tmp/uc-lance/declared-only.lance";
  protected static final String UC_CATALOG_NAME = "uc_lance_phase1";
  protected static final String UC_SCHEMA_NAME = "default";
  protected static final String LEGACY_TABLE_NAME = "legacy_embeddings";
  protected static final String NON_LANCE_TABLE_NAME = "non_lance_text";
  protected static final String UC_TABLE_NAME = "uc_table_regression";
  protected static final String LEGACY_TABLE_FULL_NAME =
      UC_CATALOG_NAME + "." + UC_SCHEMA_NAME + "." + LEGACY_TABLE_NAME;
  protected static final String NON_LANCE_TABLE_FULL_NAME =
      UC_CATALOG_NAME + "." + UC_SCHEMA_NAME + "." + NON_LANCE_TABLE_NAME;
  protected static final String UC_TABLE_FULL_NAME =
      UC_CATALOG_NAME + "." + UC_SCHEMA_NAME + "." + UC_TABLE_NAME;

  protected final ObjectMapper objectMapper = new ObjectMapper();
  protected CatalogOperations catalogOperations;
  protected SchemaOperations schemaOperations;
  protected TableOperations tableOperations;
  private WebClient client;

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    client = WebClient.builder(serverConfig.getServerUrl()).build();
    catalogOperations = new SdkCatalogOperations(TestUtils.createApiClient(serverConfig));
    schemaOperations = new SdkSchemaOperations(TestUtils.createApiClient(serverConfig));
    tableOperations = new SdkTableOperations(TestUtils.createApiClient(serverConfig));
  }

  protected AggregatedHttpResponse getLance(String path) {
    return client.get(LANCE_API_PREFIX + path).aggregate().join();
  }

  protected AggregatedHttpResponse getLance(String path, Map<String, String> headers) {
    RequestHeadersBuilder builder =
        RequestHeaders.builder().method(HttpMethod.GET).path(LANCE_API_PREFIX + path);
    headers.forEach(builder::add);
    return client.execute(builder.build()).aggregate().join();
  }

  protected AggregatedHttpResponse getRaw(String path, Map<String, String> headers) {
    RequestHeadersBuilder builder = RequestHeaders.builder().method(HttpMethod.GET).path(path);
    headers.forEach(builder::add);
    return client.execute(builder.build()).aggregate().join();
  }

  protected AggregatedHttpResponse postJson(String path, String body) {
    return postJson(path, body, Map.of());
  }

  protected AggregatedHttpResponse postJson(String path, String body, Map<String, String> headers) {
    RequestHeadersBuilder builder =
        RequestHeaders.builder()
            .method(HttpMethod.POST)
            .path(LANCE_API_PREFIX + path)
            .contentType(MediaType.JSON)
            .add(HttpHeaderNames.ACCEPT, MediaType.JSON.toString());
    headers.forEach(builder::add);
    return client.execute(builder.build(), HttpData.ofUtf8(body)).aggregate().join();
  }

  protected JsonNode json(AggregatedHttpResponse response) throws IOException {
    return objectMapper.readTree(response.contentUtf8());
  }

  protected void assertSuccess(AggregatedHttpResponse response) {
    assertThat(response.status().code()).isBetween(200, 299);
  }

  protected void assertLanceErrorShape(AggregatedHttpResponse response, int expectedStatus)
      throws IOException {
    assertThat(response.status().code()).isEqualTo(expectedStatus);
    JsonNode body = json(response);
    assertThat(body.has("type")).as(response.contentUtf8()).isTrue();
    assertThat(body.has("message")).as(response.contentUtf8()).isTrue();
    assertThat(body.has("code")).as(response.contentUtf8()).isTrue();
    assertThat(body.path("message").asText()).doesNotContain("Exception stack");
  }

  protected String createNamespaceRequest() {
    return "{\"properties\":{\"purpose\":\"phase1-test\"}}";
  }

  protected String declareTableRequest(String location) {
    return "{"
        + "\"location\":\""
        + location
        + "\","
        + "\"schema\":{\"fields\":[]},"
        + "\"properties\":{\"table_type\":\"lance\",\"owner\":\"phase1-test\"}"
        + "}";
  }

  protected void createRootAndChildNamespaces() {
    assertSuccess(
        postJson("/v1/namespace/" + ROOT_NAMESPACE + "/create", createNamespaceRequest()));
    assertSuccess(
        postJson("/v1/namespace/" + CHILD_NAMESPACE + "/create", createNamespaceRequest()));
  }

  protected void createUcCatalogAndSchema() throws Exception {
    catalogOperations.createCatalog(new CreateCatalog().name(UC_CATALOG_NAME).comment("phase1"));
    schemaOperations.createSchema(
        new CreateSchema().catalogName(UC_CATALOG_NAME).name(UC_SCHEMA_NAME));
  }

  protected TableInfo createUcTable(
      String tableName, DataSourceFormat dataSourceFormat, Map<String, String> properties)
      throws Exception {
    ColumnInfo idColumn =
        new ColumnInfo()
            .name("id")
            .typeText("INTEGER")
            .typeJson("{\"type\":\"integer\"}")
            .typeName(ColumnTypeName.INT)
            .position(0)
            .nullable(false);
    return tableOperations.createTable(
        new CreateTable()
            .name(tableName)
            .catalogName(UC_CATALOG_NAME)
            .schemaName(UC_SCHEMA_NAME)
            .columns(List.of(idColumn))
            .properties(properties)
            .storageLocation(testDirectoryRoot.resolve(tableName).toString())
            .tableType(TableType.EXTERNAL)
            .dataSourceFormat(dataSourceFormat));
  }

  protected TableInfo createLegacyLanceTable() throws Exception {
    return createUcTable(
        LEGACY_TABLE_NAME,
        DataSourceFormat.TEXT,
        Map.of("table_type", "lance", "source", "legacy"));
  }

  protected TableInfo createNonLanceTextTable() throws Exception {
    return createUcTable(NON_LANCE_TABLE_NAME, DataSourceFormat.TEXT, Map.of("table_type", "text"));
  }

  protected TableInfo createUcRegressionTable() throws Exception {
    return createUcTable(UC_TABLE_NAME, DataSourceFormat.DELTA, Map.of("purpose", "regression"));
  }
}
