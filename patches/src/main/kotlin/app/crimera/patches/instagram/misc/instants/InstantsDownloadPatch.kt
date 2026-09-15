package app.crimera.patches.instagram.misc.instants

import app.crimera.patches.instagram.entity.decoder.decoderEntity
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch

private const val HOOK = "Lapp/morphe/extension/instagram/patches/instants/InstantsDownloadHook;"

@Suppress("unused")
val instantsDownloadPatch = bytecodePatch(
    name = "Download Instants",
    description = "Diagnostic build: labels each attached Instagram window while an Instant is open.",
) {
    dependsOn(decoderEntity)
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        runCatching {
            quickSnapTypes = quickSnapReferencedTypes()
            InstantItemConstructorFingerprint.method.addInstruction(
                0,
                "invoke-static {p1}, $HOOK->noteInstantMedia(Ljava/lang/Object;)V",
            )
            println("[piko] Instants: window-label diagnostic enabled")
        }.onFailure {
            println("[piko] Instant window diagnostic disabled: ${it.message}")
        }
    }
}
