package app.crimera.patches.instagram.misc.instants

import app.crimera.patches.instagram.entity.decoder.decoderEntity
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
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
private const val SURFACE_VIEW_CLASS = "Landroid/view/SurfaceView;"
private const val SURFACE_TRANSACTION_CLASS = "Landroid/view/SurfaceControl\$Transaction;"
private const val STRIP_SECURE = "$HOOK->stripSecureFlag(I)I"
private const val STRIP_SECURE_SURFACE = "$HOOK->stripSecureSurface(Z)Z"

@Suppress("unused")
val instantsDownloadPatch = bytecodePatch(
    name = "Download Instants",
    description = "Adds a download button to the Instants viewer and allows screenshots/screen recording there.",
) {
    dependsOn(decoderEntity)
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    execute {
        runCatching {
            quickSnapTypes = quickSnapReferencedTypes()
            InstantItemConstructorFingerprint.method.addInstruction(
                0, "invoke-static {p1}, $HOOK->noteInstantMedia(Ljava/lang/Object;)V"
            )

            val windowCalls = patchWindowSecureFlagCalls()
            val surfaceCalls = patchSecureSurfaceCalls()
            println("[piko] Instants: patched $windowCalls Window calls and $surfaceCalls secure-surface calls")
        }.onFailure { println("[piko] Download Instants disabled: ${it.message}") }
    }
}

context(patchContext: BytecodePatchContext)
private fun patchWindowSecureFlagCalls(): Int {
    val classes = mutableListOf<ClassDef>()
    patchContext.classDefForEach { classes += it }

    var patched = 0
    classes.forEach { classDef ->
        val mutableClass = patchContext.mutableClassDefBy(classDef)
        mutableClass.methods.forEach { method ->
            val targets = method.instructions.mapIndexedNotNull { index, instruction ->
                if (
                    instruction.opcode != Opcode.INVOKE_VIRTUAL &&
                    instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE
                ) return@mapIndexedNotNull null

                val reference = instruction.getReference<MethodReference>()
                    ?: return@mapIndexedNotNull null
                if (reference.definingClass != WINDOW_CLASS) return@mapIndexedNotNull null

                val params = reference.parameterTypes.map(CharSequence::toString)
                when {
                    reference.name == "addFlags" && params == listOf("I") -> index
                    reference.name == "setFlags" && params == listOf("I", "I") -> index
                    else -> null
                }
            }

            targets.sortedDescending().forEach { index ->
                val registers = method.instructions[index].registersUsed
                if (registers.size < 2) return@forEach
                val flagsRegister = registers[1]
                method.addInstructions(
                    index,
                    """
                    invoke-static/range {v$flagsRegister .. v$flagsRegister}, $STRIP_SECURE
                    move-result v$flagsRegister
                    """.trimIndent(),
                )
                patched++
            }
        }
    }
    return patched
}

context(patchContext: BytecodePatchContext)
private fun patchSecureSurfaceCalls(): Int {
    val classes = mutableListOf<ClassDef>()
    patchContext.classDefForEach { classes += it }

    var patched = 0
    classes.forEach { classDef ->
        val mutableClass = patchContext.mutableClassDefBy(classDef)
        mutableClass.methods.forEach { method ->
            val targets = method.instructions.mapIndexedNotNull { index, instruction ->
                if (
                    instruction.opcode != Opcode.INVOKE_VIRTUAL &&
                    instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE
                ) return@mapIndexedNotNull null

                val reference = instruction.getReference<MethodReference>()
                    ?: return@mapIndexedNotNull null
                val params = reference.parameterTypes.map(CharSequence::toString)

                when {
                    reference.definingClass == SURFACE_VIEW_CLASS &&
                        reference.name == "setSecure" && params == listOf("Z") -> Pair(index, 1)

                    reference.definingClass == SURFACE_TRANSACTION_CLASS &&
                        reference.name == "setSecure" &&
                        params == listOf("Landroid/view/SurfaceControl;", "Z") -> Pair(index, 2)

                    else -> null
                }
            }

            targets.sortedByDescending { it.first }.forEach { (index, boolPosition) ->
                val registers = method.instructions[index].registersUsed
                if (registers.size <= boolPosition) return@forEach
                val secureRegister = registers[boolPosition]
                method.addInstructions(
                    index,
                    """
                    invoke-static/range {v$secureRegister .. v$secureRegister}, $STRIP_SECURE_SURFACE
                    move-result v$secureRegister
                    """.trimIndent(),
                )
                patched++
            }
        }
    }
    return patched
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
