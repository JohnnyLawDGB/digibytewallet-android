package io.digibyte.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Identities the provenance walk resolved before a fetched parent transaction was accepted only
 * under the id it was requested by are derived again under that rule: this step discards them,
 * with the walk frontiers recorded alongside them, and the walk re-resolves each asset from the
 * chain on its next pass.
 *
 * Only those two tables, and only their rows. Both are caches (see [MIGRATION_6_7]): dropping
 * them costs a walk, never a unit. The asset rows, the outputs held out of the plain-coin set,
 * the wallet's transactions and asset names are not provenance and are left exactly as they are.
 * Until the walk has named an asset again its transfer rules read UNKNOWN, which refuses a send.
 *
 * Once per install, by construction: Room runs a step only when opening a database written at
 * an older version, so a fresh install (created at 11) never runs it and a database already at
 * 11 never runs it again. No schema change: version 11's schema is version 10's.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM asset_provenance")
        db.execSQL("DELETE FROM asset_walk_frontier")
    }
}
