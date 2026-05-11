package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2LocalUnsupportedRestTest extends BaseLancePhase2RestTest {

  @Test
  @DisplayName("P2-ERROR-016 restore table is not supported in Phase 2")
  void restoreTableIsNotSupportedInPhase2() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/restore", "{\"version\":1}");

    assertLanceErrorShapeAllowingUnmounted(response);
  }

  @Test
  @DisplayName("P2-ERROR-017 rename table is not supported in Phase 2")
  void renameTableIsNotSupportedInPhase2() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/rename", "{\"new_id\":\"prod$team_a$new\"}");

    assertLanceErrorShapeAllowingUnmounted(response);
  }

  @Test
  @DisplayName("P2-ERROR-018 schema evolution endpoints are not supported in Phase 2")
  void schemaEvolutionEndpointsAreNotSupportedInPhase2() throws Exception {
    createActiveTableFixture();

    for (String suffix :
        new String[] {"schema_metadata/update", "add_columns", "alter_columns", "drop_columns"}) {
      AggregatedHttpResponse response =
          postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/" + suffix, "{}");
      assertLanceErrorShapeAllowingUnmounted(response);
    }
  }

  @Test
  @DisplayName("P2-AUTH-016 token exchange does not use internal HTTP loopback")
  void tokenExchangeDoesNotUseInternalHttpLoopback() throws Exception {
    String source =
        Files.readString(
            Path.of(
                "server",
                "src",
                "main",
                "java",
                "io",
                "unitycatalog",
                "server",
                "service",
                "lance",
                "LanceAuthDecorator.java"));

    assertThat(source).doesNotContain("/api/1.0/unity-control/auth/tokens");
    assertThat(source).doesNotContain("WebClient", "HttpClient", "OkHttpClient");
  }

  @Test
  @DisplayName("P2-AUTH-017/P2-REG-010 non-Lance routes ignore Lance auth headers")
  void nonLanceRoutesIgnoreLanceAuthHeaders() throws Exception {
    AggregatedHttpResponse response =
        getRaw(
            "/api/2.1/unity-catalog/catalogs",
            Map.of(
                "x-api-key", "phase2-service-key",
                "x-lance-tenant-id", "tenant-a",
                "Authorization", "Bearer external-token"));

    assertThat(response.status().code()).isNotEqualTo(500);
    assertThat(response.contentUtf8()).doesNotContain("LanceAuthDecorator", "lance operation");
  }
}
