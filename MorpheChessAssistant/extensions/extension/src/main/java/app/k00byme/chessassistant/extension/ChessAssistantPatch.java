package app.k00byme.chessassistant.extension;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime code merged into Chess.com by Morphe.
 *
 * This extension is installed only from StandaloneAnalysisActivityV2.
 * It never hooks live-game activities, clicks squares, or submits moves.
 */
public final class ChessAssistantPatch {
    private static final String TAG = "morphe.chess.assistant.analysis";
    private static final String BOARD_CLASS = "com.chess.chessboard.v2.ChessBoardView";
    private static final String STOCKFISH_URL = "https://stockfish.online/api/s/v2.php";
    private static final int ENGINE_DEPTH = 12;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ChessAssistantPatch() {}

    /** Called by the Morphe bytecode hook from the standalone analysis Activity only. */
    public static void install(Activity activity) {
        if (activity == null) return;
        if (!"com.chess.features.analysis.standalonev2.StandaloneAnalysisActivityV2"
                .equals(activity.getClass().getName())) {
            return;
        }

        // onCreate is hooked before the app creates its content view, so defer installation.
        MAIN.postDelayed(() -> {
            if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
                return;
            }

            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) content;
            if (root.findViewWithTag(TAG) != null) return;

            AnalysisOverlay overlay = new AnalysisOverlay(activity);
            overlay.setTag(TAG);
            root.addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }, 1100L);
    }

    private static final class AnalysisOverlay extends FrameLayout {
        private final WeakReference<Activity> activityRef;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final TextView panel;
        private final BoardDrawing drawing;
        private final Runnable poller = new Runnable() {
            @Override public void run() {
                scanAndAnalyze();
                if (isAttachedToWindow()) {
                    handler.postDelayed(this, 1200L);
                }
            }
        };

        private volatile boolean requestInFlight;
        private volatile boolean closed;
        private String requestedFen = "";
        private String activeFen = "";
        private String bestMove = "";
        private String status = "Procurando o tabuleiro de análise…";
        private double evaluation = 0.0;
        private int mate = 0;
        private View boardView;

        AnalysisOverlay(Activity activity) {
            super(activity);
            activityRef = new WeakReference<>(activity);
            setClipChildren(false);
            setClipToPadding(false);
            setClickable(false);
            setFocusable(false);

            drawing = new BoardDrawing(activity);
            drawing.setClickable(false);
            drawing.setFocusable(false);
            addView(drawing, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
            ));

            panel = new TextView(activity);
            panel.setText(status);
            panel.setTextColor(0xFFFFFFFF);
            panel.setTextSize(12.5f);
            panel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            panel.setPadding(dp(activity, 12), dp(activity, 9), dp(activity, 12), dp(activity, 9));

            GradientDrawable background = new GradientDrawable();
            background.setColor(0xE820242B);
            background.setCornerRadius(dp(activity, 12));
            background.setStroke(dp(activity, 1), 0x665B6472);
            panel.setBackground(background);
            panel.setElevation(dp(activity, 7));
            panel.setClickable(false);
            panel.setFocusable(false);

            FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END
            );
            int margin = dp(activity, 10);
            panelParams.topMargin = margin;
            panelParams.rightMargin = margin;
            addView(panel, panelParams);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            handler.removeCallbacks(poller);
            handler.post(poller);
        }

        @Override protected void onDetachedFromWindow() {
            closed = true;
            handler.removeCallbacksAndMessages(null);
            worker.shutdownNow();
            super.onDetachedFromWindow();
        }

        private void scanAndAnalyze() {
            Activity activity = activityRef.get();
            if (closed || activity == null || activity.isFinishing()) return;

            View content = activity.findViewById(android.R.id.content);
            View found = findBoard(content);
            if (found == null) {
                setStatus("Aguardando o tabuleiro da análise…");
                return;
            }
            boardView = found;

            String fen = readFen(found);
            if (!isFen(fen)) {
                setStatus("Aguardando uma posição FEN válida…");
                return;
            }

            activeFen = fen;
            drawing.setBoard(found);

            if (!fen.equals(requestedFen) && !requestInFlight) {
                requestedFen = fen;
                requestAnalysis(fen);
            }
            drawing.setEngine(bestMove, evaluation, mate, isBoardFlipped(found));
        }

        private void requestAnalysis(final String fen) {
            requestInFlight = true;
            setStatus("Stockfish analisando…");
            try {
                worker.execute(() -> {
                    EngineResult result = fetchStockfish(fen);
                    MAIN.post(() -> {
                        requestInFlight = false;
                        if (closed || !isAttachedToWindow()) return;
                        if (!fen.equals(activeFen)) return;

                        if (result.error != null) {
                            setStatus("Stockfish indisponível: " + result.error);
                            bestMove = "";
                            drawing.setEngine("", 0.0, 0, isBoardFlipped(boardView));
                            return;
                        }

                        bestMove = result.bestMove;
                        evaluation = result.evaluation;
                        mate = result.mate;
                        String evalText = result.mate != 0
                                ? "Mate em " + result.mate
                                : String.format(java.util.Locale.US, "%+.2f", result.evaluation);
                        setStatus("STOCKFISH  •  profundidade " + ENGINE_DEPTH
                                + "\nAvaliação: " + evalText
                                + "\nMelhor lance: " + (bestMove.isEmpty() ? "indisponível" : bestMove));
                        drawing.setEngine(bestMove, evaluation, mate, isBoardFlipped(boardView));
                    });
                });
            } catch (RuntimeException ex) {
                requestInFlight = false;
                setStatus("Não foi possível iniciar a análise.");
            }
        }

        private void setStatus(String value) {
            if (closed) return;
            MAIN.post(() -> {
                if (!closed && isAttachedToWindow()) panel.setText(value);
            });
        }
    }

    private static View findBoard(View view) {
        if (view == null) return null;
        if (BOARD_CLASS.equals(view.getClass().getName())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findBoard(group.getChildAt(i));
                if (match != null) return match;
            }
        }
        return null;
    }

    private static String readFen(View board) {
        try {
            Object position = board.getClass().getMethod("getPosition").invoke(board);
            if (position == null) return "";
            Object value = position.getClass().getMethod("o").invoke(position);
            return value instanceof String ? ((String) value).trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isBoardFlipped(View board) {
        if (board == null) return false;
        String[] names = {"getFlipBoard", "isBoardFlipped", "getBoardFlipped"};
        for (String name : names) {
            try {
                Object value = board.getClass().getMethod(name).invoke(board);
                if (value instanceof Boolean) return (Boolean) value;
            } catch (Throwable ignored) {
                // This app version does not expose that getter on this board implementation.
            }
        }
        return false;
    }

    private static boolean isFen(String fen) {
        if (fen == null || fen.isEmpty() || fen.length() > 120) return false;
        String[] fields = fen.split("\\s+");
        if (fields.length != 6) return false;
        if (!fields[1].matches("[wb]")) return false;
        if (!fields[2].matches("(-|[KQkq]+)")) return false;
        if (!fields[3].matches("(-|[a-h][36])")) return false;
        return fields[0].matches("[rnbqkpRNBQKP1-8/]+")
                && fields[4].matches("\\d+")
                && fields[5].matches("\\d+");
    }

    private static EngineResult fetchStockfish(String fen) {
        HttpURLConnection connection = null;
        try {
            String query = "fen=" + URLEncoder.encode(fen, "UTF-8")
                    + "&depth=" + ENGINE_DEPTH;
            URL url = new URL(STOCKFISH_URL + "?" + query);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "ChessAssistant-Morphe/1.0");

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) return EngineResult.error("HTTP " + code);

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }

            if (code < 200 || code >= 300) return EngineResult.error("HTTP " + code);
            JSONObject json = new JSONObject(body.toString());
            if (!json.optBoolean("success", false)) {
                return EngineResult.error("a API recusou a posição");
            }

            String bestMoveField = json.optString("bestmove", "");
            Matcher moveMatcher = Pattern.compile(
                    "bestmove\\s+([a-h][1-8][a-h][1-8][qrbn]?)"
            ).matcher(bestMoveField);
            String move = moveMatcher.find() ? moveMatcher.group(1) : "";

            int mateIn = 0;
            Object mateValue = json.opt("mate");
            if (mateValue instanceof Number) mateIn = ((Number) mateValue).intValue();

            double eval = json.optDouble("evaluation", 0.0);
            return new EngineResult(move, eval, mateIn, null);
        } catch (Exception ex) {
            String message = ex.getMessage();
            if (message == null || message.trim().isEmpty()) message = "falha de conexão";
            if (message.length() > 70) message = message.substring(0, 70);
            return EngineResult.error(message);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static final class EngineResult {
        final String bestMove;
        final double evaluation;
        final int mate;
        final String error;

        EngineResult(String bestMove, double evaluation, int mate, String error) {
            this.bestMove = bestMove;
            this.evaluation = evaluation;
            this.mate = mate;
            this.error = error;
        }

        static EngineResult error(String error) {
            return new EngineResult("", 0.0, 0, error);
        }
    }

    private static final class BoardDrawing extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final WeakReference<Context> contextRef;
        private View board;
        private String bestMove = "";
        private double evaluation = 0.0;
        private int mate = 0;
        private boolean flipped;

        BoardDrawing(Context context) {
            super(context);
            contextRef = new WeakReference<>(context);
            setWillNotDraw(false);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setBoard(View value) {
            if (board != value) board = value;
            invalidate();
        }

        void setEngine(String move, double eval, int mateIn, boolean isFlipped) {
            bestMove = move == null ? "" : move;
            evaluation = eval;
            mate = mateIn;
            flipped = isFlipped;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (board == null || !board.isShown() || board.getWidth() <= 0 || board.getHeight() <= 0) {
                return;
            }

            int[] boardLocation = new int[2];
            int[] overlayLocation = new int[2];
            board.getLocationOnScreen(boardLocation);
            getLocationOnScreen(overlayLocation);

            float size = Math.min(board.getWidth(), board.getHeight());
            float left = boardLocation[0] - overlayLocation[0]
                    + (board.getWidth() - size) / 2.0f;
            float top = boardLocation[1] - overlayLocation[1]
                    + (board.getHeight() - size) / 2.0f;
            float square = size / 8.0f;

            // Evaluation bar along the board's left edge. White occupies the upper segment.
            float normalized = (float) (0.5 + 0.5 * Math.tanh(evaluation / 3.5));
            if (mate > 0) normalized = 1.0f;
            if (mate < 0) normalized = 0.0f;
            float barWidth = dp(getContext(), 5);
            float barGap = dp(getContext(), 4);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xCC111318);
            canvas.drawRoundRect(left - barGap - barWidth, top, left - barGap,
                    top + size, dp(getContext(), 2), dp(getContext(), 2), paint);
            paint.setColor(0xFFF4F1E8);
            canvas.drawRoundRect(left - barGap - barWidth, top,
                    left - barGap, top + size * (1.0f - normalized),
                    dp(getContext(), 2), dp(getContext(), 2), paint);

            if (bestMove.length() < 4) return;
            int fromFile = bestMove.charAt(0) - 'a';
            int fromRank = bestMove.charAt(1) - '1';
            int toFile = bestMove.charAt(2) - 'a';
            int toRank = bestMove.charAt(3) - '1';
            if (fromFile < 0 || fromFile > 7 || toFile < 0 || toFile > 7
                    || fromRank < 0 || fromRank > 7 || toRank < 0 || toRank > 7) return;

            int fromColumn = flipped ? 7 - fromFile : fromFile;
            int fromRow = flipped ? fromRank : 7 - fromRank;
            int toColumn = flipped ? 7 - toFile : toFile;
            int toRow = flipped ? toRank : 7 - toRank;

            float startX = left + (fromColumn + 0.5f) * square;
            float startY = top + (fromRow + 0.5f) * square;
            float endX = left + (toColumn + 0.5f) * square;
            float endY = top + (toRow + 0.5f) * square;

            paint.setColor(0xE6F4C542);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(dp(getContext(), 3), square * 0.075f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(startX, startY, endX, endY, paint);

            float angle = (float) Math.atan2(endY - startY, endX - startX);
            float head = square * 0.28f;
            Path arrowHead = new Path();
            arrowHead.moveTo(endX, endY);
            arrowHead.lineTo(
                    endX - head * (float) Math.cos(angle - Math.PI / 5.5),
                    endY - head * (float) Math.sin(angle - Math.PI / 5.5)
            );
            arrowHead.lineTo(
                    endX - head * (float) Math.cos(angle + Math.PI / 5.5),
                    endY - head * (float) Math.sin(angle + Math.PI / 5.5)
            );
            arrowHead.close();
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(arrowHead, paint);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
