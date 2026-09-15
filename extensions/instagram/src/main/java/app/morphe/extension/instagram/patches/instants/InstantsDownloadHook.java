package app.morphe.extension.instagram.patches.instants;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.TextView;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class InstantsDownloadHook {
    private static final long LABEL_LIFETIME_MS = 45_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Map<View, Integer> WINDOW_IDS = new WeakHashMap<>();
    private static final Map<View, TextView> WINDOW_LABELS = new WeakHashMap<>();
    private static int nextWindowId = 1;
    private static volatile long lastInstantSeenAt;

    /**
     * Called from the QuickSnap/Instant item constructor.
     *
     * This diagnostic build deliberately does NOT touch FLAG_SECURE or SurfaceView security.
     * It only asks Android for the root views of windows already attached to this process and
     * draws a small label inside each root's ViewGroupOverlay. That lets us identify which
     * exact window becomes the Instants viewer without changing the window's behavior.
     */
    public static void noteInstantMedia(Object media) {
        try {
            if (media == null) return;
            lastInstantSeenAt = System.currentTimeMillis();

            // The Instant window may be attached a few frames after its media item is created.
            scheduleScan(0L);
            scheduleScan(100L);
            scheduleScan(250L);
            scheduleScan(500L);
            scheduleScan(900L);
            scheduleScan(1500L);
            scheduleScan(2500L);
            scheduleScan(4000L);
            scheduleScan(7000L);

            MAIN.postDelayed(() -> {
                try {
                    if (System.currentTimeMillis() - lastInstantSeenAt >= LABEL_LIFETIME_MS) {
                        clearLabels();
                    }
                } catch (Throwable ignored) {
                }
            }, LABEL_LIFETIME_MS + 1000L);
        } catch (Throwable t) {
            Logger.printException(() -> "Instant window diagnostic failed", t);
        }
    }

    private static void scheduleScan(long delayMs) {
        MAIN.postDelayed(InstantsDownloadHook::labelAllWindows, delayMs);
    }

    private static void labelAllWindows() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;

        try {
            List<View> roots = WindowInspector.getGlobalWindowViews();
            for (View root : roots) {
                if (!(root instanceof ViewGroup)) continue;
                ViewGroup group = (ViewGroup) root;
                group.post(() -> installOrUpdateLabel(group));
            }
        } catch (Throwable t) {
            Logger.printException(() -> "WindowInspector scan failed", t);
        }
    }

    private static void installOrUpdateLabel(ViewGroup root) {
        try {
            final int id;
            TextView label;

            synchronized (WINDOW_IDS) {
                Integer existingId = WINDOW_IDS.get(root);
                if (existingId == null) {
                    existingId = nextWindowId++;
                    WINDOW_IDS.put(root, existingId);
                }
                id = existingId;

                label = WINDOW_LABELS.get(root);
                if (label == null) {
                    label = createLabel(root.getContext());
                    WINDOW_LABELS.put(root, label);
                    root.getOverlay().add(label);
                }
            }

            label.setText(describeWindow(root, id));

            int maxWidth = Math.max(dp(root.getContext(), 220), root.getWidth() - dp(root.getContext(), 24));
            label.measure(
                    View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            );

            int left = dp(root.getContext(), 12);
            int top = dp(root.getContext(), 48);
            int width = label.getMeasuredWidth();
            int height = label.getMeasuredHeight();
            label.layout(left, top, left + width, top + height);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to label Instagram window", t);
        }
    }

    private static TextView createLabel(Context context) {
        TextView label = new TextView(context);
        label.setTextColor(Color.WHITE);
        label.setTextSize(14f);
        label.setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7));
        label.setClickable(false);
        label.setFocusable(false);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE6000000);
        background.setCornerRadius(dp(context, 8));
        background.setStroke(dp(context, 2), 0xFFFFFFFF);
        label.setBackground(background);
        return label;
    }

    private static String describeWindow(View root, int id) {
        boolean secure = false;
        int type = -1;
        String title = "";

        try {
            ViewGroup.LayoutParams params = root.getLayoutParams();
            if (params instanceof WindowManager.LayoutParams) {
                WindowManager.LayoutParams windowParams = (WindowManager.LayoutParams) params;
                secure = (windowParams.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
                type = windowParams.type;
                CharSequence rawTitle = windowParams.getTitle();
                if (rawTitle != null) title = rawTitle.toString();
            }
        } catch (Throwable ignored) {
        }

        if (title.length() > 60) title = title.substring(0, 60);
        String rootName = root.getClass().getSimpleName();
        if (rootName == null || rootName.isEmpty()) rootName = root.getClass().getName();

        StringBuilder out = new StringBuilder();
        out.append("W").append(id);
        if (secure) out.append("  [SECURE]");
        out.append("  type=").append(type);
        out.append("\n").append(rootName);
        if (!title.isEmpty()) out.append("\n").append(title);
        return out.toString();
    }

    private static void clearLabels() {
        MAIN.post(() -> {
            try {
                synchronized (WINDOW_IDS) {
                    for (Map.Entry<View, TextView> entry : WINDOW_LABELS.entrySet()) {
                        View root = entry.getKey();
                        TextView label = entry.getValue();
                        if (root instanceof ViewGroup && label != null) {
                            try {
                                ((ViewGroup) root).getOverlay().remove(label);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    WINDOW_LABELS.clear();
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
