package io.unitycatalog.server.persist.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Date;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(
    name = "uc_lance_assets",
    indexes = {
      @Index(name = "idx_lance_asset_namespace", columnList = "namespace_id,name"),
      @Index(name = "idx_lance_asset_path_key", columnList = "path_key")
    },
    uniqueConstraints = {
      @UniqueConstraint(columnNames = {"namespace_id", "name"}),
      @UniqueConstraint(columnNames = {"path_key"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
public class LanceAssetDAO extends IdentifiableDAO {

  @Column(name = "namespace_id", nullable = false)
  private UUID namespaceId;

  @Column(name = "asset_type", nullable = false)
  private String assetType;

  @Column(name = "path_key", nullable = false, length = 2048)
  private String pathKey;

  @Column(name = "canonical_identifier", nullable = false, length = 2048)
  private String canonicalIdentifier;

  @Column(name = "state", nullable = false)
  private String state;

  @Column(name = "owner")
  private String owner;

  @Column(name = "created_at", nullable = false)
  private Date createdAt;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "updated_at", nullable = false)
  private Date updatedAt;

  @Column(name = "updated_by")
  private String updatedBy;
}
