package app.crimera.patches.instagram.misc.instants

import app.crimera.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
import app.morphe.patcher.Fingerprint

internal const val QUICK_SNAP_REPOSITORY_CLASS = "Lcom/instagram/quicksnap/data/repository/QuickSnapRepository;"
internal var quickSnapTypes: Set<String> = emptySet()

internal object InstantItemConstructorFingerprint : Fingerprint(
    name = "<init>",
    custom = { methodDef, classDef ->
        classDef.type in quickSnapTypes &&
            methodDef.parameters.firstOrNull()?.type == MEDIA_CLASS_NAME &&
            classDef.fields.any { it.type == MEDIA_CLASS_NAME }
    },
)