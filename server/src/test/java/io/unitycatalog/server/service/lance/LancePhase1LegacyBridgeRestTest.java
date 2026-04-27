package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import org.hibernate.Session;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase1")
@Disabled("Enable after Lance Phase 1 legacy bridge service is implemented.")
class LancePhase1LegacyBridgeRestTest extends BaseLancePhase1RestTest {
  private static final String LEGACY_LANCE_ID =
      UC_CATALOG_NAME + "$" + UC_SCHEMA_NAME + "$" + LEGACY_TABLE_NAME;
  private static final String NON_LANCE_ID =
      UC_CATALOG_NAME + "$" + UC_SCHEMA_NAME + "$" + NON_LANCE_TABLE_NAME;

  @Test
  @DisplayName("P1-LEGACY-001 legacy table is identified by EXTERNAL+TEXT+table_type=lance")
  void legacyTableIsCorrectlyIdentified() throws Exception {
    createUcCatalogAndSchema();
    createLegacyLanceTable();

    AggregatedHttpResponse describe = postJson("/v1/table/" + LEGACY_LANCE_ID + "/describe", "{}");
    assertSuccess(describe);

    assertThat(json(describe).path("legacy_bridge").asBoolean()).isTrue();
    assertThat(json(describe).path("source_table_full_name").asText())
        .isEqualTo(LEGACY_TABLE_FULL_NAME);
    assertThat(json(describe).path("properties").path("table_type").asText()).isEqualTo("lance");
  }

  @Test
  @DisplayName("P1-LEGACY-002/P1-LEGACY-003 legacy table appears in list and describe")
  void legacyTableAppearsInListAndDescribe() throws Exception {
    createUcCatalogAndSchema();
    createLegacyLanceTable();

    AggregatedHttpResponse list =
        getLance("/v1/namespace/" + UC_CATALOG_NAME + "$" + UC_SCHEMA_NAME + "/table/list");
    assertSuccess(list);
    assertThat(json(list).path("tables").toString()).contains(LEGACY_LANCE_ID);

    AggregatedHttpResponse describe = postJson("/v1/table/" + LEGACY_LANCE_ID + "/describe", "{}");
    assertSuccess(describe);
    assertThat(json(describe).path("location").asText()).isNotBlank();
    assertThat(json(describe).path("legacy_bridge").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P1-LEGACY-004 legacy table exists returns true")
  void legacyTableExistsReturnsTrue() throws Exception {
    createUcCatalogAndSchema();
    createLegacyLanceTable();

    AggregatedHttpResponse exists = postJson("/v1/table/" + LEGACY_LANCE_ID + "/exists", "{}");
    assertSuccess(exists);
    assertThat(json(exists).path("exists").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P1-LEGACY-005 non-Lance EXTERNAL table is not recognized as legacy")
  void nonLanceExternalTableNotRecognized() throws Exception {
    createUcCatalogAndSchema();
    createNonLanceTextTable();

    AggregatedHttpResponse exists = postJson("/v1/table/" + NON_LANCE_ID + "/exists", "{}");
    assertSuccess(exists);
    assertThat(json(exists).path("exists").asBoolean()).isFalse();

    AggregatedHttpResponse list =
        getLance("/v1/namespace/" + UC_CATALOG_NAME + "$" + UC_SCHEMA_NAME + "/table/list");
    assertSuccess(list);
    assertThat(json(list).path("tables").toString()).doesNotContain(NON_LANCE_ID);
  }

  @Test
  @DisplayName("P1-LEGACY-006 legacy read does not pollute Lance metadata tables")
  void legacyReadDoesNotPolluteLanceTables() throws Exception {
    createUcCatalogAndSchema();
    createLegacyLanceTable();

    assertSuccess(postJson("/v1/table/" + LEGACY_LANCE_ID + "/describe", "{}"));
    assertSuccess(postJson("/v1/table/" + LEGACY_LANCE_ID + "/exists", "{}"));

    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      Number assetCount =
          (Number)
              session
                  .createNativeQuery(
                      "select count(*) from uc_lance_assets where path_key = :pathKey")
                  .setParameter(
                      "pathKey", UC_CATALOG_NAME + "/" + UC_SCHEMA_NAME + "/" + LEGACY_TABLE_NAME)
                  .getSingleResult();
      Number tableCount =
          (Number)
              session
                  .createNativeQuery(
                      "select count(*) from uc_lance_tables where asset_id in "
                          + "(select id from uc_lance_assets where path_key = :pathKey)")
                  .setParameter(
                      "pathKey", UC_CATALOG_NAME + "/" + UC_SCHEMA_NAME + "/" + LEGACY_TABLE_NAME)
                  .getSingleResult();

      assertThat(assetCount.intValue()).isEqualTo(0);
      assertThat(tableCount.intValue()).isEqualTo(0);
    }
  }

  @Test
  @DisplayName("P1-CRED-004 legacy bridge table can vend credentials from storage location")
  void legacyBridgeTableCanVendCredentialsFromStorageLocation() throws Exception {
    createUcCatalogAndSchema();
    createLegacyLanceTable();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + LEGACY_LANCE_ID + "/describe", "{\"vend_credentials\":true}");
    assertSuccess(response);
    assertThat(json(response).path("legacy_bridge").asBoolean()).isTrue();
    assertThat(json(response).path("storage_options").isObject()).isTrue();
  }

  @Test
  @DisplayName("P1-TBL-015 legacy table deregister does not mutate the source UC table")
  void legacyTableDeregisterDoesNotMutateSourceUcTable() throws Exception {
    createUcCatalogAndSchema();
    createLegacyLanceTable();

    AggregatedHttpResponse deregister =
        postJson(
            "/v1/table/" + LEGACY_LANCE_ID + "/deregister", "{\"delete_physical_data\":false}");
    assertLanceErrorShape(deregister, 501);

    assertThat(tableOperations.getTable(LEGACY_TABLE_FULL_NAME).getName())
        .isEqualTo(LEGACY_TABLE_NAME);
  }
}
