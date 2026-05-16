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
    name = "uc_lance_versions",
    indexes = {
      @Index(name = "idx_lance_versions_asset", columnList = "asset_id"),
      @Index(name = "idx_lance_versions_timestamp", columnList = "timestamp")
    },
    uniqueConstraints = {@UniqueConstraint(columnNames = {"asset_id", "version"})})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Builder
public class LanceVersionDAO {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "version", nullable = false)
  private Long version;

  @Column(name = "operation", nullable = false)
  private String operation;

  @Column(name = "timestamp", nullable = false)
  private Date timestamp;

  @Column(name = "manifest_path", length = 4096)
  private String manifestPath;

  @Column(name = "manifest_size")
  private Long manifestSize;

  @Column(name = "etag")
  private String etag;

  @Column(name = "metadata_json", length = 65535)
  private String metadataJson;

  @Column(name = "stats_json", length = 65535)
  private String statsJson;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Date createdAt;
}
