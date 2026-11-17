# Chess Assistant — Morphe patch

A Morphe patch bundle for **Chess.com Android 4.10.28-googleplay** (package com.chess).

## What it does

- Adds a Stockfish evaluation panel and best-move arrow to the standalone analysis screen.
- Reads FEN from the analysis board's native position object.
- Requests analysis from Stockfish Online when the position changes.
- Does not make moves, click board squares, or send moves to Chess.com.

The hook targets StandaloneAnalysisActivityV2 only. It is not installed on live-game activities.

## Privacy and network

The current implementation sends the board FEN to stockfish.online for analysis. It does not intentionally send a username, account token, game identifier, or other account data. Avoid using it with private positions if you do not want the FEN sent to that service.

## Compatibility

Only Chess.com Android 4.10.28-googleplay is declared as a supported target. The XAPK file type is declared because the supplied package was an XAPK split bundle. Other app versions are untested.

## Build

The GitHub Actions workflow named "Build Morphe Chess Assistant" builds the .mpp file and uploads it as an artifact. To build locally with the Morphe toolchain installed, run:

    gradle buildAndroid

The output is in patches/build/libs/.

## Important

This patch is experimental. Its bytecode build and runtime integration must pass verification before it can be considered ready to install.
