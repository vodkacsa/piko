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
private const val DIALOG_CLASS = "Landroid/app/Dialog;"
private const val POPUP_WINDOW_CLASS = "Landroid/widget/PopupWindow;"
private const val WINDOW_MANAGER_CLASS = "Landroid/view/WindowManager;"
private const val VIEW_MANAGER_CLASS = "Landroid/view/ViewManager;"

private const val NOTE_WINDOW = "$HOOK->noteWindow(Landroid/view/Window;)V"
private const val NOTE_DIALOG = "$HOOK->noteDialog(Landroid/app/Dialog;)V"
private const val NOTE_POPUP = "$HOOK->notePopup(Landroid/widget/PopupWindow;)V"
private const val NOTE_ROOT = "$HOOK->noteWindowRoot(Landroid/view/View;)V"

@Suppress("unused")
val instantsDownloadPatch = bytecodePatch(
    name = "Download Instants",
    description = "Diagnostic build: labels concrete Instagram windows while an Instant is open.",
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

            val windowCalls = patchWindowCalls()
            val dialogs = patchDialogShows()
            val popups = patchPopupShows()
            val roots = patchWindowManagerRoots()
            println(
                "[piko] Instants window diagnostic: $windowCalls Window calls, " +
                    "$dialogs Dialog shows, $popups PopupWindow shows, $roots WindowManager roots",
            )
        }.onFailure {
            println("[piko] Instant window diagnostic disabled: ${it.message}")
        }
    }
}

context(patchContext: BytecodePatchContext)
private fun patchWindowCalls(): Int {
    val classes = mutableListOf<ClassDef>()
    patchContext.classDefForEach { classes += it }

    var patched = 0
    classes.forEach { classDef ->
        val mutableClass = patchContext.mutableClassDefBy(classDef)
        mutableClass.methods.forEach { method ->
            val targets = method.instructions.mapIndexedNotNull { index, instruction ->
                if (!instruction.opcode.isInvoke()) return@mapIndexedNotNull null
                val reference = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
                if (reference.definingClass != WINDOW_CLASS) return@mapIndexedNotNull null
                index
            }

            targets.sortedDescending().forEach { index ->
                val registers = method.instructions[index].registersUsed
                if (registers.isEmpty()) return@forEach
                val windowRegister = registers[0]
                method.addInstructions(
                    index,
                    "invoke-static/range {v$windowRegister .. v$windowRegister}, $NOTE_WINDOW",
                )
                patched++
            }
        }
    }
    return patched
}

context(patchContext: BytecodePatchContext)
private fun patchDialogShows(): Int {
    val classes = mutableListOf<ClassDef>()
    patchContext.classDefForEach { classes += it }

    var patched = 0
    classes.forEach { classDef ->
        val mutableClass = patchContext.mutableClassDefBy(classDef)
        mutableClass.methods.forEach { method ->
            val targets = method.instructions.mapIndexedNotNull { index, instruction ->
                if (!instruction.opcode.isInvoke()) return@mapIndexedNotNull null
                val reference = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
                if (
                    reference.definingClass == DIALOG_CLASS &&
                    reference.name == "show" &&
                    reference.parameterTypes.isEmpty()
                ) index else null
            }

            targets.sortedDescending().forEach { index ->
                val registers = method.instructions[index].registersUsed
                if (registers.isEmpty()) return@forEach
                val dialogRegister = registers[0]
                method.addInstructions(
                    index,
                    "invoke-static/range {v$dialogRegister .. v$dialogRegister}, $NOTE_DIALOG",
                )
                patched++
            }
        }
    }
    return patched
}

context(patchContext: BytecodePatchContext)
private fun patchPopupShows(): Int {
    val classes = mutableListOf<ClassDef>()
    patchContext.classDefForEach { classes += it }

    var patched = 0
    classes.forEach { classDef ->
        val mutableClass = patchContext.mutableClassDefBy(classDef)
        mutableClass.methods.forEach { method ->
            val targets = method.instructions.mapIndexedNotNull { index, instruction ->
                if (!instruction.opcode.isInvoke()) return@mapIndexedNotNull null
                val reference = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
                if (
                    reference.definingClass == POPUP_WINDOW_CLASS &&
                    (reference.name == "showAtLocation" || reference.name == "showAsDropDown")
                ) index else null
            }

            targets.sortedDescending().forEach { index ->
                val registers = method.instructions[index].registersUsed
                if (registers.isEmpty()) return@forEach
                val popupRegister = registers[0]
                method.addInstructions(
                    index,
                    "invoke-static/range {v$popupRegister .. v$popupRegister}, $NOTE_POPUP",
                )
                patched++
            }
        }
    }
    return patched
}

context(patchContext: BytecodePatchContext)
private fun patchWindowManagerRoots(): Int {
    val classes = mutableListOf<ClassDef>()
    patchContext.classDefForEach { classes += it }

    var patched = 0
    classes.forEach { classDef ->
        val mutableClass = patchContext.mutableClassDefBy(classDef)
        mutableClass.methods.forEach { method ->
            val targets = method.instructions.mapIndexedNotNull { index, instruction ->
                if (!instruction.opcode.isInvoke()) return@mapIndexedNotNull null
                val reference = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
                if (reference.definingClass != WINDOW_MANAGER_CLASS && reference.definingClass != VIEW_MANAGER_CLASS) {
                    return@mapIndexedNotNull null
                }
                if (reference.name != "addView" && reference.name != "updateViewLayout") {
                    return@mapIndexedNotNull null
                }
                val params = reference.parameterTypes.map(CharSequence::toString)
                if (params.size != 2 || params[0] != "Landroid/view/View;") return@mapIndexedNotNull null
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
