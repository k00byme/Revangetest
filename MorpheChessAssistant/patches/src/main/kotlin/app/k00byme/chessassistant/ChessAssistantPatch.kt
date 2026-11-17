package app.k00byme.chessassistant

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch

private const val EXTENSION_CLASS = "Lapp/k00byme/chessassistant/extension/ChessAssistantPatch;"

@Suppress("unused")
val chessAssistantPatch = patch(
    name = "Stockfish analysis overlay",
    description = "Shows an evaluation and best-move arrow on the standalone analysis board.",
    default = true
) {
    compatibleWith(Constants.CHESS_COM_COMPATIBILITY)
    extendWith("extensions/extension.mpe")

    execute {
        // Hook only Chess.com's standalone analysis ActivityV2.
        // The patch is not attached to live-game activities or the in-game board.
        val analysisActivityFingerprint = Fingerprint(
            definingClass = "Lcom/chess/features/analysis/standalonev2/StandaloneAnalysisActivityV2;",
            name = "onCreate",
            returnType = "V",
            parameters = listOf("Landroid/os/Bundle;"),
            isStatic = false
        )

        val method = analysisActivityFingerprint.methodOrNull
            ?: throw analysisActivityFingerprint.patchException()

        // install() only posts a delayed UI task. The original onCreate continues normally.
        method.addInstructions(
            0,
            """
                invoke-static {p0}, $EXTENSION_CLASS;->install(Landroid/app/Activity;)V
            """
        )
    }
}
