package app.morphe.extension.instagram.patches.instants;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;
import java.util.WeakHashMap;

@SuppressWarnings("unused")
public final class InstantsDownloadHook {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Window, Integer> IDS = new WeakHashMap<>();
    private static final Map<Window, TextView> LABELS = new WeakHashMap<>();
    private static int nextId = 1;
    private static volatile long lastInstantSeenAt;

    public static void noteInstantMedia(Object media) {
        if (media == null) return;
        lastInstantSeenAt = System.currentTimeMillis();
    }

    /**
     * Called only from Instagram's own FLAG_SECURE controller, immediately before it invokes
     * addFlags/setFlags/clearFlags on a concrete Window. Nothing is changed here. We simply
     * remember and label that exact Window.
     */
    public static void noteSecureWindow(Window window) {
        if (window == null) return;

        final int id;
        final boolean isNew;
        synchronized (IDS) {
            Integer existing = IDS.get(window);
            if (existing == null) {
                existing = nextId++;
                IDS.put(window, existing);
                isNew = true;
            } else {
                isNew = false;
            }
            id = existing;
        }

        // The actual flag mutation happens just after this callback. Re-check after the current
        // call returns so [SECURE] reflects the resulting Window attributes.
        MAIN.post(() -> showLabel(window, id, isNew));
        MAIN.postDelayed(() -> showLabel(window, id, false), 40L);
        MAIN.postDelayed(() -> showLabel(window, id, false), 160L);
        MAIN.postDelayed(() -> showLabel(window, id, false), 500L);
    }

    private static void showLabel(Window window, int id, boolean toastIfNew) {
        try {
            View decor = window.getDecorView();
            if (decor == null) return;

            WindowManager.LayoutParams attrs = window.getAttributes();
            boolean secure = attrs != null &&
                    (attrs.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
            int type = attrs != null ? attrs.type : -1;
            String title = "";
            if (attrs != null && attrs.getTitle() != null) title = attrs.getTitle().toString();
            if (title.length() > 70) title = title.substring(0, 70);

            String rootName = decor.getClass().getSimpleName();
            if (rootName == null || rootName.isEmpty()) rootName = decor.getClass().getName();

            StringBuilder text = new StringBuilder();
            text.append("S").append(id);
            if (secure) text.append(" [SECURE]");
            text.append(" type=").append(type);
            text.append("\n").append(rootName);
            if (!title.isEmpty()) text.append("\n").append(title);

            if (toastIfNew) {
                try {
                    Toast.makeText(
                            decor.getContext().getApplicationContext(),
                            "Secure-window hook: S" + id + (secure ? " [SECURE]" : "") + "\n" + rootName,
                            Toast.LENGTH_LONG
                    ).show();
                } catch (Throwable ignored) {
                }
            }

            if (!(decor instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) decor;
            TextView label;

            synchronized (IDS) {
                label = LABELS.get(window);
                if (label == null) {
                    label = createLabel(decor.getContext());
                    LABELS.put(window, label);
                    group.getOverlay().add(label);
                }
            }

            label.setText(text.toString());
            int maxWidth = Math.max(dp(decor.getContext(), 260), decor.getWidth() - dp(decor.getContext(), 16));
            label.measure(
                    View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            );

            int left = dp(decor.getContext(), 10);
            int top = dp(decor.getContext(), 54 + ((id - 1) % 5) * 58);
            int width = label.getMeasuredWidth();
            int height = label.getMeasuredHeight();
            label.layout(left, top, left + width, top + height);
            label.setElevation(100000f);
            label.bringToFront();
        } catch (Throwable ignored) {
        }
    }

    private static TextView createLabel(Context context) {
        TextView label = new TextView(context);
        label.setTextColor(Color.WHITE);
        label.setTextSize(15f);
        label.setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7));
        label.setClickable(false);
        label.setFocusable(false);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE7B001C);
        bg.setCornerRadius(dp(context, 7));
        bg.setStroke(dp(context, 2), Color.WHITE);
        label.setBackground(bg);
        return label;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
