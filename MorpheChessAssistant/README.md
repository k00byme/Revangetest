# Chess Assistant — Morphe patch

Morphe patch bundle for **Chess.com Android 4.10.28-googleplay** (package `com.chess`).

## Supported screens

- **Play vs Bots:** hooks the dedicated `BotGameActivityV2` used by Chess.com's bot games, including bot sessions started from its online or offline flow.
- **Standalone analysis:** hooks `StandaloneAnalysisActivityV2` for analysis and review.
- **Competitive games against human players:** the general `RealGameActivity` is intentionally not hooked.

## Visual features

- Stockfish score/evaluation panel.
- Best-move arrow drawn over the native board.
- Blue highlight on the move's origin square and green highlight on its destination.
- Board orientation handling for flipped and normal views.
- Old arrows/highlights are cleared when the position changes or the board disappears.
- The extension never moves a piece, clicks a square, or sends a move to Chess.com.

## Engine connectivity and privacy

The current patch requests analysis from `https://stockfish.online/api/s/v2.php` over HTTPS at depth 12. **The assistant needs an internet connection to obtain new engine suggestions**, even when the Chess.com bot game itself is being played in its offline mode. If the network is unavailable, the overlay reports that Stockfish is unavailable and clears the previous suggestion. This build does not bundle an on-device Stockfish engine.

The board FEN is sent to the external Stockfish Online service for analysis. The extension does not intentionally send a username, account token, game identifier, or other account data. Do not use it with private positions if you do not want their FEN sent to that service.

## Compatibility

Only Chess.com Android `4.10.28-googleplay` is declared as a supported target. The XAPK file type is declared because the app package is distributed as an XAPK split bundle. Other app versions are untested.

## Build

The GitHub Actions workflow **Build Morphe Chess Assistant** builds the `.mpp` file and uploads it as an artifact. To build locally with the Morphe toolchain installed, run:

```bash
gradle buildAndroid
```

The output is in `patches/build/libs/`.

## Verification status

The target APK structure was inspected. The bot and standalone-analysis activity entry points, native `ChessBoardView`, and standard-position FEN accessor were confirmed in version 4.10.28. The Morphe bundle has been compiled successfully. However, runtime behaviour still needs confirmation on an Android device with the target Chess.com version; successful compilation alone does not prove that the overlay renders correctly in every bot session.
