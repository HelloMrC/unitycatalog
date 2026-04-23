# LanceDB Adaptation Source Archive

This page archives the main references used to study how Unity Catalog could support LanceDB in a way that stays compatible with upstream Lance evolution.

Access date for external sources: `2026-04-20`.

This archive stores links, local file references, and extracted conclusions. It does not attempt to mirror the full upstream documents.

## Local Unity Catalog repository references

### Documentation references

- `docs/usage/volumes.md`
  - Relevant because UC documentation currently positions volumes as a good place for non-tabular assets and explicitly mentions Lance datasets.
  - Design implication: volumes are a valid fallback, but they are weaker than tables if the goal is governed Lance metadata and namespace-aware clients.

- `docs/usage/tables/uniform.md`
  - Relevant because it shows that current Iceberg support in this repository is based on Delta UniForm metadata.
  - Design implication: Lance should not be forced through the same path unless Lance itself is being projected through Iceberg, which is not the upstream Lance model.

- `docs/integrations/unity-catalog-trino.md`
  - Relevant because it documents the current Iceberg REST integration style.
  - Design implication: route structure and service composition for a Lance service can follow a similar mounting pattern without reusing Iceberg semantics.

- `docs/quickstart.md`
  - Relevant because it distinguishes tables, functions, and Lance volumes in the sample hierarchy.
  - Design implication: the current docs already separate "Lance as files or volumes" from "Delta as tables".

### Server and API references

- `server/src/main/java/io/unitycatalog/server/UnityCatalogServer.java`
  - Relevant because it mounts both Iceberg REST and Delta REST services in the same server process.
  - Design implication: a Lance service could also be mounted as a peer service under its own path prefix.

- `server/src/main/java/io/unitycatalog/server/service/IcebergRestCatalogService.java`
  - Relevant because it shows how UC exposes a protocol-specific facade on top of internal metadata.
  - Design implication: Lance support can follow the same "adapter over UC metadata" pattern, but with Lance semantics instead of Iceberg semantics.

- `server/src/main/java/io/unitycatalog/server/service/delta/DeltaRestCatalogService.java`
  - Relevant because it shows UC already serves a second protocol family with its own config endpoint and endpoint list.
  - Design implication: there is no architectural need to overload Iceberg or Delta routes for Lance.

- `server/src/main/java/io/unitycatalog/server/persist/TableRepository.java`
  - Relevant because it shows:
    - external tables are general-purpose
    - managed tables are currently restricted to Delta
  - Design implication: Lance can be introduced immediately as an external-table-backed integration, while managed Lance should be treated as a later project.

- `server/src/main/java/io/unitycatalog/server/service/TemporaryTableCredentialsService.java`
  - Relevant because it already vends short-lived credentials for tables.
  - Design implication: Lance clients or a Lance adapter can reuse UC credential vending instead of storing long-lived object-store secrets in metadata.

- `api/all.yaml`
  - Relevant because it defines the public `TableInfo` / `CreateTable` schema and the `DataSourceFormat` enum.
  - Design implication: UC does not currently expose a `LANCE` data source format, so a first implementation either needs:
    - the upstream Lance Unity approach (`TEXT` plus `properties.table_type=lance`), or
    - a schema change that adds `LANCE` across OpenAPI, generated models, and clients.

## External official Lance and LanceDB references

### Lance Namespace and integrations

- [Lance Namespace Spec](https://lance.org/format/namespace/)
  - Relevant because it defines the abstraction that Lance wants integrations to target.
  - Key takeaway: Lance Namespace is the long-term compatibility layer, and integrations for systems like Unity Catalog are expected to map into that contract.

- [Unity Catalog Lance Namespace Implementation Spec](https://lance.org/format/namespace/integrations/unity/)
  - Relevant because it is the most direct upstream guidance for this project.
  - Key takeaways:
    - Lance tables map to UC `EXTERNAL` tables.
    - Table identity is `catalog.schema.table`.
    - `storage_location` points to the Lance table root.
    - Lance table detection uses `properties.table_type=lance`.
    - Because UC does not natively expose `LANCE`, the spec uses `data_source_format=TEXT` as a generic placeholder.

- [Lance REST Namespace Catalog Spec](https://lance.org/format/namespace/rest/catalog-spec/)
  - Relevant because it defines the REST contract for a Lance Namespace server.
  - Key takeaway: REST is a first-class protocol in the Lance ecosystem, not just a private implementation detail.

- [Lance REST Namespace Implementation Spec](https://lance.org/format/namespace/rest/impl-spec/)
  - Relevant because it enumerates the full operation surface.
  - Key takeaways:
    - metadata operations cover namespace and table registration
    - the REST surface also includes query, indexing, versions, tags, and transaction-related operations

- [Apache Iceberg REST Catalog Lance Namespace Implementation Spec](https://lance.org/format/namespace/integrations/iceberg/)
  - Relevant because it shows how Lance integrates with Iceberg when Iceberg is the catalog protocol.
  - Key takeaway: when Lance uses Iceberg REST, it wraps Lance tables in an Iceberg-compatible metadata shell; this is a different strategy from the Unity integration.

### LanceDB product and API references

- [LanceDB SDKs and REST API Reference](https://docs.lancedb.com/api-reference)
  - Relevant because it describes the official client and REST entry points.
  - Key takeaway: remote and enterprise LanceDB usage already assumes a namespace-aware and REST-capable backend.

- [LanceDB Basic Table Operations](https://docs.lancedb.com/tables)
  - Relevant because it documents common table lifecycle and query behavior.
  - Key takeaway: Lance tables are mutable and include search, schema evolution, versioning, and indexing expectations that go beyond simple metadata registration.

- [LanceDB Cloud](https://docs.lancedb.com/cloud)
  - Relevant because it clarifies that managed deployments keep the same underlying Lance data model while moving operations behind a remote service boundary.
  - Key takeaway: a UC-backed Lance metadata layer should assume future remote-service style usage, not just local file access.

- [LanceDB Java SDK](https://lancedb.github.io/lancedb/java/java/)
  - Relevant because it documents remote operation through the Lance REST Namespace API.
  - Key takeaway: at least part of the Lance ecosystem already expects namespace-backed remote access patterns.

## Working interpretation from the source set

The combined source set points to the following working interpretation:

1. UC should own governance and metadata for Lance tables.
2. Lance Namespace compatibility should be the main interface contract.
3. A metadata-only implementation is a good first phase.
4. A full Lance data plane is not required on day one, but the design should leave room for it.
5. Iceberg REST should not be used as the primary abstraction for Lance in UC.
