package app.k00byme.chessassistant

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch

private const val EXTENSION_CLASS = "Lapp/k00byme/chessassistant/extension/ChessAssistantPatch;"

@Suppress("unused")
val chessAssistantPatch = patch(
    name = "Stockfish board assistant",
    description = "Shows a Stockfish move arrow, source/destination highlights and evaluation in online games, bot games and standalone analysis.",
    default = true
) {
    compatibleWith(Constants.CHESS_COM_COMPATIBILITY)
    extendWith("extensions/extension.mpe")

    execute {
        fun hookActivity(className: String) {
            val fingerprint = Fingerprint(
                definingClass = className,
                name = "onCreate",
                returnType = "V",
                parameters = listOf("Landroid/os/Bundle;"),
                isStatic = false
            )
            val method = fingerprint.methodOrNull ?: throw fingerprint.patchException()
            method.addInstructions(
                0,
                """
                    invoke-static {p0}, ${EXTENSION_CLASS}->install(Landroid/app/Activity;)V
                """
            )
        }

        // Standalone analysis / post-game review.
        hookActivity("Lcom/chess/features/analysis/standalonev2/StandaloneAnalysisActivityV2;")

        // Dedicated Chess.com Play-vs-Bots activity.
        hookActivity("Lcom/chess/features/versusbots/game/BotGameActivityV2;")

        // Main activity used by Chess.com's online human games and game formats.
        hookActivity("Lcom/chess/realchess/ui/game/RealGameActivity;")
    }
}
