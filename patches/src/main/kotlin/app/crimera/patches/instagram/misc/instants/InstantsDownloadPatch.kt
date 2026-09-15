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
private const val NOTE_OBJECT = "$HOOK->noteObject(Ljava/lang/Object;)V"
private const val NOTE_ROOT = "$HOOK->noteWindowRoot(Landroid/view/View;)V"

private val WINDOWISH_METHOD_NAMES = setOf(
    "addFlags",
    "clearFlags",
    "setFlags",
    "getDecorView",
    "peekDecorView",
    "getAttributes",
    "setAttributes",
    "getWindow",
    "getWindowManager",
)

private val ROOT_METHOD_NAMES = setOf(
    "addView",
    "updateViewLayout",
    "setContentView",
    "addContentView",
)

@Suppress("unused")
val instantsDownloadPatch = bytecodePatch(
    name = "Download Instants",
    description = "Diagnostic build: labels activity roots and window-like objects while an Instant is open.",
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

            // Do not depend on the compile-time receiver type being exactly android.view.Window.
            // Instagram frequently keeps windows/managers behind subclasses and wrappers.
            val receivers = patchWindowishReceivers()
            val roots = patchRootViewArguments()
            println("[piko] Instants diagnostic: $receivers window-ish receivers, $roots root-view calls")
        }.onFailure {
            println("[piko] Instant window diagnostic disabled: ${it.message}")
        }
    }
}

context(patchContext: BytecodePatchContext)
private fun patchWindowishReceivers(): Int {
    val classes = mutableListOf<ClassDef>()
    patchContext.classDefForEach { classes += it }

    var patched = 0
    classes.forEach { classDef ->
        val mutableClass = patchContext.mutableClassDefBy(classDef)
        mutableClass.methods.forEach { method ->
            val targets = method.instructions.mapIndexedNotNull { index, instruction ->
                if (!instruction.opcode.isInvoke()) return@mapIndexedNotNull null
                val reference = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
                if (reference.name !in WINDOWISH_METHOD_NAMES) return@mapIndexedNotNull null
                index
            }

            targets.sortedDescending().forEach { index ->
                val registers = method.instructions[index].registersUsed
                if (registers.isEmpty()) return@forEach
                val receiver = registers[0]
                method.addInstructions(
                    index,
                    "invoke-static/range {v$receiver .. v$receiver}, $NOTE_OBJECT",
                )
                patched++
            }
        }
    }
    return patched
}

context(patchContext: BytecodePatchContext)
private fun patchRootViewArguments(): Int {
    val classes = mutableListOf<ClassDef>()
    patchContext.classDefForEach { classes += it }

    var patched = 0
    classes.forEach { classDef ->
        val mutableClass = patchContext.mutableClassDefBy(classDef)
        mutableClass.methods.forEach { method ->
            val targets = method.instructions.mapIndexedNotNull { index, instruction ->
                if (!instruction.opcode.isInvoke()) return@mapIndexedNotNull null
                val reference = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
                if (reference.name !in ROOT_METHOD_NAMES) return@mapIndexedNotNull null
                val params = reference.parameterTypes.map(CharSequence::toString)
                if (params.isEmpty() || params[0] != "Landroid/view/View;") return@mapIndexedNotNull null
                index
            }

            targets.sortedDescending().forEach { index ->
                val registers = method.instructions[index].registersUsed
                if (registers.size < 2) return@forEach
                val viewRegister = registers[1]
                method.addInstructions(
                    index,
                    "invoke-static/range {v$viewRegister .. v$viewRegister}, $NOTE_ROOT",
                )
                patched++
            }
        }
    }
    return patched
}

private fun Opcode.isInvoke(): Boolean =
    this == Opcode.INVOKE_VIRTUAL ||
        this == Opcode.INVOKE_VIRTUAL_RANGE ||
        this == Opcode.INVOKE_INTERFACE ||
        this == Opcode.INVOKE_INTERFACE_RANGE

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
