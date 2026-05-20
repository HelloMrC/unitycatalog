package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.service.lance.backend.LanceTestEchoExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase3")
class LancePhase3SchemaRestTest extends BaseLancePhase2RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  // ========== P3-SYNC-003: syncSchema 测试 ==========

  @Test
  @DisplayName("P3-SYNC-003-01: syncSchema updates table schema")
  void syncSchemaUpdatesTableSchema() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse syncResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/schema",
            "{\"version\":10,\"schema\":{\"fields\":[{\"name\":\"id\",\"type\":\"int64\"},"
                + "{\"name\":\"name\",\"type\":\"string\"}]},"
                + "\"operation\":\"add_columns\",\"columns_added\":[{\"name\":\"name\"}]}");

    assertSuccess(syncResponse);
    JsonNode synced = json(syncResponse);
    assertThat(synced.path("version").asLong()).isEqualTo(10);
    assertThat(synced.path("schema").isObject()).isTrue();
    assertThat(synced.path("operation").asText()).isEqualTo("add_columns");
    assertThat(synced.path("columns_added").isArray()).isTrue();
  }

  @Test
  @DisplayName("P3-SYNC-003-02: syncSchema updates with optional version and stats")
  void syncSchemaUpdatesWithOptionalVersionAndStats() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse syncResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/schema",
            "{\"version\":15,\"schema\":{\"fields\":[{\"name\":\"id\",\"type\":\"int64\"}]},"
                + "\"stats\":{\"num_rows\":1000}}");

    assertSuccess(syncResponse);
    JsonNode synced = json(syncResponse);
    assertThat(synced.path("version").asLong()).isEqualTo(15);
    assertThat(synced.path("schema").isObject()).isTrue();
  }

  @Test
  @DisplayName("P3-SYNC-003-03: syncSchema rejects missing schema")
  void syncSchemaRejectsMissingSchema() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/schema", "{\"version\":10}");

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("message").asText()).contains("schema is required");
  }

  @Test
  @DisplayName("P3-SYNC-003-04: syncSchema rejects declared-only table")
  void syncSchemaRejectsDeclaredOnlyTable() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_DECLARED_TABLE_ID + "/metadata/sync/schema",
            "{\"schema\":{\"fields\":[]}}");

    assertLanceErrorShape(response, 409);
    assertThat(json(response).path("message").asText()).contains("must be materialized");
  }

  // ========== Schema 操作类型边界值测试 ==========

  @Test
  @DisplayName("P3-SCHEMA-OP-01: syncSchema accepts add_columns operation")
  void syncSchemaAcceptsAddColumnsOperation() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/schema",
            "{\"version\":11,\"schema\":{\"fields\":[{\"name\":\"id\",\"type\":\"int64\"},"
                + "{\"name\":\"embedding\",\"type\":\"fixed_size_list<float,128>\"}]},"
                + "\"operation\":\"add_columns\"}");

    assertSuccess(response);
    assertThat(json(response).path("operation").asText()).isEqualTo("add_columns");
  }

  @Test
  @DisplayName("P3-SCHEMA-OP-02: syncSchema accepts alter_columns operation")
  void syncSchemaAcceptsAlterColumnsOperation() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/schema",
            "{\"version\":12,\"schema\":{\"fields\":[{\"name\":\"id\",\"type\":\"int32\"}]},"
                + "\"operation\":\"alter_columns\",\"columns_altered\":[{\"name\":\"id\"}]}");

    assertSuccess(response);
    assertThat(json(response).path("operation").asText()).isEqualTo("alter_columns");
  }

  @Test
  @DisplayName("P3-SCHEMA-OP-03: syncSchema accepts drop_columns operation")
  void syncSchemaAcceptsDropColumnsOperation() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/schema",
            "{\"version\":13,\"schema\":{\"fields\":[{\"name\":\"id\",\"type\":\"int64\"}]},"
                + "\"operation\":\"drop_columns\",\"columns_dropped\":[{\"name\":\"name\"}]}");

    assertSuccess(response);
    assertThat(json(response).path("operation").asText()).isEqualTo("drop_columns");
  }

  @Test
  @DisplayName("P3-SCHEMA-OP-04: syncSchema accepts without operation")
  void syncSchemaAcceptsWithoutOperation() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/schema",
            "{\"version\":14,\"schema\":{\"fields\":[{\"name\":\"id\",\"type\":\"int64\"}]}}");

    assertSuccess(response);
    assertThat(json(response).has("operation")).isFalse();
  }

  // ========== Schema 字段类型边界值测试 ==========

  @Test
  @DisplayName("P3-SCHEMA-TYPE-01: syncSchema accepts primitive field types")
  void syncSchemaAcceptsPrimitiveFieldTypes() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/schema",
            "{\"version\":20,\"schema\":{\"fields\":"
                + "[{\"name\":\"f_int32\",\"type\":\"int32\"},"
                + "{\"name\":\"f_int64\",\"type\":\"int64\"},"
                + "{\"name\":\"f_float32\",\"type\":\"float32\"},"
                + "{\"name\":\"f_float64\",\"type\":\"float64\"},"
                + "{\"name\":\"f_string\",\"type\":\"string\"},"
                + "{\"name\":\"f_bool\",\"type\":\"bool\"}]}}");

    assertSuccess(response);
    JsonNode schema = json(response).path("schema");
    assertThat(schema.path("fields").size()).isEqualTo(6);
  }

  @Test
  @DisplayName("P3-SCHEMA-TYPE-02: syncSchema accepts nested field types")
  void syncSchemaAcceptsNestedFieldTypes() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/schema",
            "{\"version\":21,\"schema\":{\"fields\":"
                + "[{\"name\":\"vector\",\"type\":\"fixed_size_list<float,128>\"},"
                + "{\"name\":\"metadata\",\"type\":\"struct<key:string,value:string>\"}]}}");

    assertSuccess(response);
    JsonNode schema = json(response).path("schema");
    assertThat(schema.path("fields").size()).isEqualTo(2);
  }

  @Test
  @DisplayName("P3-SCHEMA-TYPE-03: syncSchema accepts list field types")
  void syncSchemaAcceptsListFieldTypes() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/schema",
            "{\"version\":22,\"schema\":{\"fields\":"
                + "[{\"name\":\"tags\",\"type\":\"list<string>\"}]}}");

    assertSuccess(response);
    assertThat(json(response).path("schema").isObject()).isTrue();
  }

  @Test
  @DisplayName("P3-SCHEMA-TYPE-04: syncSchema accepts complex nested schema")
  void syncSchemaAcceptsComplexNestedSchema() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/schema",
            "{\"version\":23,\"schema\":{\"fields\":"
                + "[{\"name\":\"id\",\"type\":\"int64\",\"nullable\":false},"
                + "{\"name\":\"embedding\",\"type\":{\"type\":\"fixed_size_list\","
                + "\"element_type\":\"float32\",\"size\":256},\"nullable\":true},"
                + "{\"name\":\"attributes\",\"type\":{\"type\":\"struct\","
                + "\"fields\":[{\"name\":\"key\",\"type\":\"string\"},"
                + "{\"name\":\"value\",\"type\":\"string\"}]}}]}}");

    assertSuccess(response);
    assertThat(json(response).path("schema").isObject()).isTrue();
  }
}
