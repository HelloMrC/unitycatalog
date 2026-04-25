package io.unitycatalog.server.persist.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "uc_lance_tables")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Builder
public class LanceTableDAO {

  @Id
  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "storage_location", length = 4096)
  private String storageLocation;

  @Column(name = "table_uri", length = 4096)
  private String tableUri;

  @Column(name = "arrow_schema_json", length = 65535)
  private String arrowSchemaJson;

  @Column(name = "storage_options_template_json", length = 65535)
  private String storageOptionsTemplateJson;

  @Column(name = "is_only_declared", nullable = false)
  private Boolean isOnlyDeclared;

  @Column(name = "legacy_uc_table_id")
  private UUID legacyUcTableId;

  @Column(name = "current_version")
  private Long currentVersion;

  @Column(name = "managed_versioning", nullable = false)
  private Boolean managedVersioning;

  @Column(name = "stats_json", length = 65535)
  private String statsJson;
}
