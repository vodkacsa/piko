package app.morphe.extension.instagram.patches.instants;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

@SuppressWarnings("unused")
public final class InstantsDownloadHook {
    private static final long ACTIVE_MS = 60_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Map<View, Integer> IDS = new WeakHashMap<>();
    private static final Map<View, TextView> LABELS = new WeakHashMap<>();
    private static final Map<View, String> SOURCES = new WeakHashMap<>();
    private static final Map<View, WeakReference<Window>> WINDOWS = new WeakHashMap<>();

    private static int nextId = 1;
    private static volatile long lastInstantSeenAt;

    public static void noteInstantMedia(Object media) {
        if (media == null) return;
        lastInstantSeenAt = System.currentTimeMillis();

        scheduleRefresh(0L);
        scheduleRefresh(80L);
        scheduleRefresh(200L);
        scheduleRefresh(450L);
        scheduleRefresh(900L);
        scheduleRefresh(1500L);
        scheduleRefresh(2500L);
        scheduleRefresh(4000L);
        scheduleRefresh(7000L);

        MAIN.postDelayed(() -> {
            if (!isInstantActive()) clearLabels();
        }, ACTIVE_MS + 1000L);
    }

    /** Called before Instagram invokes a method on an actual android.view.Window. */
    public static void noteWindow(Window window) {
        if (window == null) return;
        try {
            View decor = window.getDecorView();
            rememberRoot(decor, "Window", window);
        } catch (Throwable ignored) {
        }
    }

    /** Called around Dialog.show() so dialog-owned windows get their own stable W# label. */
    public static void noteDialog(Dialog dialog) {
        if (dialog == null) return;
        tryDialog(dialog);
        MAIN.postDelayed(() -> tryDialog(dialog), 50L);
        MAIN.postDelayed(() -> tryDialog(dialog), 200L);
    }

    /** Called around PopupWindow show calls. */
    public static void notePopup(PopupWindow popup) {
        if (popup == null) return;
        tryPopup(popup);
        MAIN.postDelayed(() -> tryPopup(popup), 50L);
        MAIN.postDelayed(() -> tryPopup(popup), 200L);
    }

    /** Called when Instagram hands a root View to WindowManager/ViewManager. */
    public static void noteWindowRoot(View root) {
        if (root == null) return;
        rememberRoot(root, "WindowManager", null);
        MAIN.postDelayed(() -> {
            try {
                View actualRoot = root.getRootView();
                if (actualRoot != null) rememberRoot(actualRoot, "WindowManager", null);
            } catch (Throwable ignored) {
            }
        }, 50L);
    }

    private static void tryDialog(Dialog dialog) {
        try {
            Window window = dialog.getWindow();
            if (window == null) return;
            View decor = window.getDecorView();
            rememberRoot(decor, "Dialog", window);
        } catch (Throwable ignored) {
        }
    }

    private static void tryPopup(PopupWindow popup) {
        try {
            View content = popup.getContentView();
            if (content == null) return;
            View root = content.getRootView();
            rememberRoot(root != null ? root : content, "PopupWindow", null);
        } catch (Throwable ignored) {
        }
    }

    private static void rememberRoot(View candidate, String source, Window window) {
        if (candidate == null) return;
        final View root;
        try {
            View resolved = candidate.getRootView();
            root = resolved != null ? resolved : candidate;
        } catch (Throwable ignored) {
            return;
        }

        synchronized (IDS) {
            if (!IDS.containsKey(root)) IDS.put(root, nextId++);
            if (!SOURCES.containsKey(root) || "WindowManager".equals(SOURCES.get(root))) {
                SOURCES.put(root, source);
            }
            if (window != null) WINDOWS.put(root, new WeakReference<>(window));
        }

        if (isInstantActive()) {
            root.post(() -> installOrUpdateLabel(root));
        }
    }

    private static boolean isInstantActive() {
        long age = System.currentTimeMillis() - lastInstantSeenAt;
        return age >= 0 && age <= ACTIVE_MS;
    }

    private static void scheduleRefresh(long delayMs) {
        MAIN.postDelayed(InstantsDownloadHook::refreshKnownRoots, delayMs);
    }

    private static void refreshKnownRoots() {
        if (!isInstantActive()) return;

        final List<View> roots;
        synchronized (IDS) {
            roots = new ArrayList<>(IDS.keySet());
        }

        for (View root : roots) {
            if (root == null) continue;
            try {
                root.post(() -> installOrUpdateLabel(root));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void installOrUpdateLabel(View root) {
        if (!(root instanceof ViewGroup) || !isInstantActive()) return;

        try {
            ViewGroup group = (ViewGroup) root;
            final int id;
            final String source;
            final Window window;
            TextView label;

            synchronized (IDS) {
                Integer found = IDS.get(root);
                if (found == null) {
                    found = nextId++;
                    IDS.put(root, found);
                }
                id = found;
                source = SOURCES.get(root);

                WeakReference<Window> ref = WINDOWS.get(root);
                window = ref != null ? ref.get() : null;

                label = LABELS.get(root);
                if (label == null) {
                    label = createLabel(root.getContext());
                    LABELS.put(root, label);
                    group.getOverlay().add(label);
                }
            }

            label.setText(describe(root, window, id, source));

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
            label.bringToFront();
        } catch (Throwable ignored) {
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
        label.setElevation(dp(context, 100));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6000000);
        bg.setCornerRadius(dp(context, 8));
        bg.setStroke(dp(context, 2), 0xFFFFFFFF);
        label.setBackground(bg);
        return label;
    }

    private static String describe(View root, Window window, int id, String source) {
        boolean secure = false;
        int type = -1;
        String title = "";

        try {
            WindowManager.LayoutParams params = null;
            if (window != null) {
                params = window.getAttributes();
            } else if (root.getLayoutParams() instanceof WindowManager.LayoutParams) {
                params = (WindowManager.LayoutParams) root.getLayoutParams();
            }

            if (params != null) {
                secure = (params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
                type = params.type;
                CharSequence rawTitle = params.getTitle();
                if (rawTitle != null) title = rawTitle.toString();
            }
        } catch (Throwable ignored) {
        }

        if (title.length() > 80) title = title.substring(0, 80);
        String rootName = root.getClass().getSimpleName();
        if (rootName == null || rootName.isEmpty()) rootName = root.getClass().getName();

        StringBuilder out = new StringBuilder();
        out.append("W").append(id);
        if (secure) out.append(" [SECURE]");
        out.append(" type=").append(type);
        if (source != null) out.append(" via ").append(source);
        out.append("\n").append(rootName);
        if (!title.isEmpty()) out.append("\n").append(title);
        return out.toString();
    }

    private static void clearLabels() {
        MAIN.post(() -> {
            synchronized (IDS) {
                for (Map.Entry<View, TextView> entry : LABELS.entrySet()) {
                    View root = entry.getKey();
                    TextView label = entry.getValue();
                    if (root instanceof ViewGroup && label != null) {
                        try {
                            ((ViewGroup) root).getOverlay().remove(label);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                LABELS.clear();
            }
        });
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
