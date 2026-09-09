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
private const val STRIP_SECURE = "$HOOK->stripSecureFlag(I)I"
private const val NOTE_WINDOW_FLAGS = "$HOOK->noteWindowFlagCall(Landroid/view/Window;)V"
private const val NOTE_CLEAR_FLAG = "$HOOK->noteClearFlag(I)V"
private const val NOTE_WINDOW_CLEAR = "$HOOK->noteWindowClearCall(Landroid/view/Window;)V"

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

            val secureCalls = patchWindowSecureFlagCalls()
            println("[piko] Instants: patched $secureCalls Window secure-flag calls")
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
                    reference.name == "addFlags" && params == listOf("I") -> Triple(index, reference.name, 2)
                    reference.name == "setFlags" && params == listOf("I", "I") -> Triple(index, reference.name, 3)
                    reference.name == "clearFlags" && params == listOf("I") -> Triple(index, reference.name, 2)
                    else -> null
                }
            }

            targets.sortedByDescending { it.first }.forEach { (index, name, expectedRegisters) ->
                val registers = method.instructions[index].registersUsed
                if (registers.size != expectedRegisters) return@forEach

                val windowRegister = registers[0]
                val flagsRegister = registers[1]

                when (name) {
                    "addFlags", "setFlags" -> {
                        method.addInstructions(
                            index,
                            """
                            invoke-static/range {v$flagsRegister .. v$flagsRegister}, $STRIP_SECURE
                            move-result v$flagsRegister
                            invoke-static/range {v$windowRegister .. v$windowRegister}, $NOTE_WINDOW_FLAGS
                            """.trimIndent(),
                        )
                    }

                    "clearFlags" -> {
                        method.addInstructions(
                            index,
                            """
                            invoke-static/range {v$flagsRegister .. v$flagsRegister}, $NOTE_CLEAR_FLAG
                            invoke-static/range {v$windowRegister .. v$windowRegister}, $NOTE_WINDOW_CLEAR
                            """.trimIndent(),
                        )
                    }
                }
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
