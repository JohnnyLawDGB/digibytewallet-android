package io.digibyte.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Purges every stored issuer attribution, because all of them are claims.
 *
 * `asset_metadata.issuerAddress` used to be filled from the IPFS metadata document — a file the
 * minter writes — by reading `issuerAddress`, then `issuer.address`, then bare `issuer`. The
 * Asset Detail screen rendered it as "Issuer Address", copyable, as though it were established.
 *
 * It is the easiest field in the file to forge, and forging it is the whole point: copy an asset,
 * re-mint it, name the original creator, and the claim travels with the image. A wallet that
 * displays it makes the forgery more convincing than it would be alone. (The bare `issuer` key is
 * usually a username in practice, so the row could show a handle under a label saying "Address".)
 *
 * The column now holds only what the chain proves — the owner of input[0] of the issuance
 * transaction, which is the outpoint the assetId is derived from, so it cannot be copied without
 * having paid for the issuance. No schema change is needed for that; what IS needed is getting
 * rid of the rows already carrying a claim, since nothing else would ever overwrite them: the
 * metadata cache is only rewritten when an asset is re-fetched, so a claimed value could sit in
 * the UI indefinitely.
 *
 * Purging is safe. The proven value is re-fetched from the provider on the next metadata refresh,
 * and until then the row simply does not render — absent beats wrong.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE asset_metadata SET issuerAddress = NULL")
    }
}
