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
    name = "uc_lance_transactions",
    indexes = {
      @Index(name = "idx_lance_transactions_table", columnList = "table_asset_id"),
      @Index(name = "idx_lance_transactions_status", columnList = "status")
    },
    uniqueConstraints = {@UniqueConstraint(columnNames = {"transaction_key"})})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Builder
public class LanceTransactionDAO {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "transaction_key", nullable = false)
  private String transactionKey;

  @Column(name = "table_asset_id", nullable = false)
  private UUID tableAssetId;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "actions_json", length = 65535)
  private String actionsJson;

  @Column(name = "commit_metadata_json", length = 65535)
  private String commitMetadataJson;

  @Column(name = "created_at", nullable = false)
  private Date createdAt;

  @Column(name = "updated_at")
  private Date updatedAt;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "updated_by")
  private String updatedBy;
}
