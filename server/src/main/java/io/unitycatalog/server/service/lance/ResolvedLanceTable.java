package io.unitycatalog.server.service.lance;

import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.service.lance.backend.LanceTableRef;

record ResolvedLanceTable(
    LanceTableRef tableRef, LanceAssetDAO assetDAO, LanceTableDAO tableDAO, boolean legacyBridge) {}
