# LanceDB and Unity Catalog API Compatibility

This page summarizes where LanceDB and Unity Catalog align cleanly, where they diverge, and which differences matter for implementation.

## Short answer

There is no hard REST route conflict between the existing UC services and a future Lance service.

The real differences are in:

- namespace depth
- table identification
- supported operations
- managed-table semantics
- query and indexing responsibilities

## Existing UC protocol layout

The current server already hosts multiple protocol families:

- core UC REST APIs for catalogs, schemas, tables, volumes, functions, models, and credentials
- Iceberg REST under the `/iceberg` path
- Delta REST configuration under `/delta/v1/config` plus Delta-related paths under the UC base path

Design implication: a Lance service can be mounted as a peer protocol family, for example under `/api/2.1/unity-catalog/lance`, without colliding with the current Iceberg or Delta routes.

## Compatibility matrix

| Topic | Unity Catalog today | Lance / LanceDB expectation | Compatibility |
| --- | --- | --- | --- |
| Namespace shape | Fixed `catalog.schema.table` hierarchy | Namespace path model; can be multi-level in general | Compatible through the upstream Unity integration, which constrains Lance to UC's 3-level model |
| Table registration | Native `tables` API | Namespace-backed table registration | Compatible |
| Table identity | Full name and table id | Namespace path plus table name | Compatible with an adapter layer |
| Data source format | No `LANCE` enum today | Lance table identity required | Compatible if using `TEXT` plus `properties.table_type=lance`, as recommended upstream |
| Managed tables | Managed path restricted to Delta | Lance has its own table and version model | Not compatible today |
| Temporary credentials | Supported for tables and paths | Needed for object-store-backed Lance tables | Compatible |
| Query API | UC is mostly control plane | Lance REST includes query operations | Gap |
| Index API | UC has no Lance index APIs | Lance REST includes vector and scalar index management | Gap |
| Version and tag API | UC table metadata does not model Lance versions or tags | Lance REST includes versions and tags | Gap |

## What does not conflict

### Route prefixes

There is room for a Lance-specific route family.

Examples:

- `/api/2.1/unity-catalog/lance/v1/namespace/...`
- `/api/2.1/unity-catalog/lance/v1/table/...`

This would mirror the current pattern where Iceberg and Delta each expose their own protocol-facing endpoints.

### External-table metadata model

UC external tables are flexible enough to carry the minimal Lance metadata that the upstream Unity integration expects:

- namespace location through `catalog_name` and `schema_name`
- table name through `name`
- Lance root path through `storage_location`
- Lance identification through table properties
- schema metadata through `columns`

### Credential vending

UC already has temporary credential APIs for tables and paths. That makes it feasible to keep UC in charge of storage authorization while a Lance adapter or Lance-facing API translates those credentials into Lance-compatible storage options.

## What does conflict in practice

### Namespace depth

Lance Namespace is more general than UC's fixed hierarchy.

The good news is that the official Lance Unity integration already defines the narrowing:

- root namespace -> UC server
- first level -> catalog
- second level -> schema
- table -> `catalog.schema.table`

So this is a modeling restriction, not a blocker.

### Format identification

UC public schemas do not expose a `LANCE` format value today.

That means you need to choose between:

- upstream-compatible first phase: use `TEXT` plus `properties.table_type=lance`
- deeper UC-native extension: add `LANCE` to the OpenAPI schema, generated models, persistence, and UI

The first option is much lower risk and matches upstream Lance documentation today.

### Managed-table semantics

UC currently restricts managed table creation to Delta.

That is a real implementation boundary. If Lance support starts as an external-table-based integration, there is no problem. If UC later needs managed Lance tables, the repository will need new staging, commit, lifecycle, and probably conflict-resolution semantics that are closer to Lance's own table model than to current managed Delta flows.

### Data-plane scope

This is the biggest gap.

Lance REST Namespace includes much more than metadata:

- query
- count
- explain and analyze
- vector and scalar index creation
- versions
- tags
- restore and related lifecycle actions

UC today is much stronger as a metadata and governance plane than as a Lance-native query service. That means a full Lance-compatible design should separate:

- metadata and control-plane responsibilities that UC already does well
- data-plane features that may need a dedicated Lance adapter or embedded Lance service

## Recommended compatibility posture

The best posture is:

1. Make UC the source of truth for namespace and table metadata.
2. Keep the UC-to-Lance mapping compatible with the upstream Unity Namespace spec.
3. Treat Lance REST data-plane operations as a separate layer that can be added incrementally.

That gives good compatibility now without blocking future Lance-native features.
