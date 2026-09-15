package app.crimera.patches.instagram.misc.instants

import app.crimera.patches.instagram.entity.decoder.decoderEntity
import app.crimera.patches.instagram.misc.privacy.AddFlagsToWindowFingerprint
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val HOOK = "Lapp/morphe/extension/instagram/patches/instants/InstantsDownloadHook;"
private const val WINDOW_CLASS = "Landroid/view/Window;"
private const val NOTE_SECURE_WINDOW = "$HOOK->noteSecureWindow(Landroid/view/Window;)V"

@Suppress("unused")
val instantsDownloadPatch = bytecodePatch(
    name = "Download Instants",
    description = "Diagnostic build: identifies the exact Window Instagram sends through its FLAG_SECURE controller.",
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

            val secureWindowHooks = patchSecureWindowController()
            println("[piko] Instants: hooked $secureWindowHooks Window call(s) in Instagram FLAG_SECURE controller")
        }.onFailure {
            println("[piko] Instant secure-window diagnostic disabled: ${it.message}")
        }
    }
}

private fun Opcode.isWindowInvoke(): Boolean =
    this == Opcode.INVOKE_VIRTUAL || this == Opcode.INVOKE_VIRTUAL_RANGE

private fun patchSecureWindowController(): Int {
    val method = AddFlagsToWindowFingerprint.method
    val targets = method.instructions.mapIndexedNotNull { index, instruction ->
        if (!instruction.opcode.isWindowInvoke()) return@mapIndexedNotNull null
        val reference = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
        if (reference.definingClass != WINDOW_CLASS) return@mapIndexedNotNull null
        if (reference.name != "addFlags" && reference.name != "setFlags" && reference.name != "clearFlags") {
            return@mapIndexedNotNull null
        }
        index
    }

    var patched = 0
    targets.sortedDescending().forEach { index ->
        val registers = method.instructions[index].registersUsed
        if (registers.isEmpty()) return@forEach
        val windowRegister = registers[0]
        method.addInstruction(
            index,
            "invoke-static/range {v$windowRegister .. v$windowRegister}, $NOTE_SECURE_WINDOW",
        )
        patched++
    }
    return patched
}

private fun ClassDef.referencedTypes(): Sequence<String> = methods.asSequence()
    .flatMap { it.implementation?.instructions?.asSequence() ?: emptySequence() }
    .mapNotNull { (it as? ReferenceInstruction)?.reference }
    .flatMap { reference -> when (reference) {
        is TypeReference -> sequenceOf(reference.type)
        is FieldReference -> sequenceOf(reference.definingClass, reference.type)
        is MethodReference -> sequenceOf(reference.definingClass, reference.returnType) +
            reference.parameterTypes.asSequence().map { it.toString() }
        else -> emptySequence()
    } }

context(patchContext: BytecodePatchContext)
private fun quickSnapReferencedTypes(): Set<String> {
    val types = mutableSetOf<String>()
    patchContext.classDefForEach { classDef ->
        if (classDef.methods.any { method ->
                method.parameterTypes.any { it.toString() == QUICK_SNAP_REPOSITORY_CLASS }
            }) {
            types += classDef.referencedTypes()
        }
    }
    return types
}
