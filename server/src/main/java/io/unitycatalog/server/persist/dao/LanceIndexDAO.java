package io.unitycatalog.server.persist.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Date;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "uc_lance_indices",
    indexes = {
      @Index(name = "idx_lance_indices_table", columnList = "table_asset_id"),
      @Index(name = "idx_lance_indices_status", columnList = "status")
    },
    uniqueConstraints = {@UniqueConstraint(columnNames = {"table_asset_id", "index_name"})})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Builder
public class LanceIndexDAO {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "table_asset_id", nullable = false)
  private UUID tableAssetId;

  @Column(name = "index_name", nullable = false)
  private String indexName;

  @Column(name = "index_type", nullable = false)
  private String indexType;

  @Column(name = "target_columns_json", nullable = false, length = 65535)
  private String targetColumnsJson;

  @Column(name = "distance_type")
  private String distanceType;

  @Column(name = "build_params_json", length = 65535)
  private String buildParamsJson;

  @Column(name = "stats_json", length = 65535)
  private String statsJson;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Date createdAt;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "updated_at")
  private Date updatedAt;

  @Column(name = "updated_by")
  private String updatedBy;
}
