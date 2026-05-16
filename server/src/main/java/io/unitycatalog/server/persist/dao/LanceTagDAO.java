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
    name = "uc_lance_tags",
    indexes = {
      @Index(name = "idx_lance_tags_table", columnList = "table_asset_id"),
      @Index(name = "idx_lance_tags_version", columnList = "version")
    },
    uniqueConstraints = {@UniqueConstraint(columnNames = {"table_asset_id", "tag_name"})})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Builder
public class LanceTagDAO {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "table_asset_id", nullable = false)
  private UUID tableAssetId;

  @Column(name = "tag_name", nullable = false)
  private String tagName;

  @Column(name = "version", nullable = false)
  private Long version;

  @Column(name = "metadata_json", length = 65535)
  private String metadataJson;

  @Column(name = "created_at", nullable = false)
  private Date createdAt;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "updated_at")
  private Date updatedAt;

  @Column(name = "updated_by")
  private String updatedBy;
}
