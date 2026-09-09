package app.crimera.patches.instagram.misc.instants

import app.crimera.patches.instagram.entity.decoder.decoderEntity
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val HOOK = "Lapp/morphe/extension/instagram/patches/instants/InstantsDownloadHook;"

@Suppress("unused")
val instantsDownloadPatch = bytecodePatch(
    name = "Download Instants",
    description = "Automatically saves received Instants when they are opened.",
) {
    dependsOn(decoderEntity)
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    execute {
        runCatching {
            quickSnapTypes = quickSnapReferencedTypes()
            InstantItemConstructorFingerprint.method.addInstruction(
                0, "invoke-static {p1}, $HOOK->noteInstantMedia(Ljava/lang/Object;)V"
            )
        }.onFailure { println("[piko] Download Instants disabled: ${it.message}") }
    }
}

private fun ClassDef.referencedTypes(): Sequence<String> = methods.asSequence()
    .flatMap { it.implementation?.instructions?.asSequence() ?: emptySequence() }
    .mapNotNull { (it as? ReferenceInstruction)?.reference }
    .flatMap { reference -> when (reference) {
        is TypeReference -> sequenceOf(reference.type)
        is FieldReference -> sequenceOf(reference.definingClass, reference.type)
        is MethodReference -> sequenceOf(reference.definingClass, reference.returnType) + reference.parameterTypes.asSequence().map { it.toString() }
        else -> emptySequence()
    } }

context(patchContext: BytecodePatchContext)
private fun quickSnapReferencedTypes(): Set<String> {
    val types = mutableSetOf<String>()
    patchContext.classDefForEach { classDef ->
        if (classDef.methods.any { method -> method.parameterTypes.any { it.toString() == QUICK_SNAP_REPOSITORY_CLASS } }) {
            types += classDef.referencedTypes()
        }
    }
    return types
}