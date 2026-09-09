package app.morphe.extension.instagram.patches.instants;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.patches.download.DownloadUtils;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings("unused")
public final class InstantsDownloadHook {
    private static final long INSTANT_MATCH_WINDOW_MS = 60_000L;
    private static final long BUTTON_FALLBACK_HIDE_MS = 120_000L;

    private static volatile String currentId;
    private static volatile String currentUsername;
    private static volatile boolean currentVideo;
    private static volatile String currentUrl;
    private static volatile long lastInstantSeenAt;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<View, ImageView> buttons = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> secureFlagAttempt = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> secureFlagClear = new ThreadLocal<>();

    public static void noteInstantMedia(Object media) {
        try {
            if (media == null) return;

            MediaData md = new MediaData(media);
            String id = safeStr(() -> md.getMediaPkId());
            if (id == null || id.isEmpty()) return;

            boolean video = safeBool(() -> md.isVideo());
            String url = video
                    ? safeStr(() -> md.getVideoLink())
                    : safeStr(() -> md.getImageLink());
            if (url == null || !url.startsWith("http")) return;

            currentId = id;
            currentVideo = video;
            currentUrl = url;
            currentUsername = safeStr(() -> md.getUserData().getUsername());
            lastInstantSeenAt = System.currentTimeMillis();
        } catch (Throwable t) {
            Logger.printException(() -> "Instant media hook failed", t);
        }
    }

    /**
     * Called immediately before Instagram calls Window.addFlags/setFlags.
     * FLAG_SECURE is removed before Android receives it, matching the strategy
     * used by InstaEclipse's screenshot-permission hook.
     */
    public static int stripSecureFlag(int flags) {
        boolean hadSecure = (flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        secureFlagAttempt.set(hadSecure);
        return flags & ~WindowManager.LayoutParams.FLAG_SECURE;
    }

    /** Called with the exact Window on which Instagram attempted to set flags. */
    public static void noteWindowFlagCall(Window window) {
        try {
            boolean attemptedSecure = Boolean.TRUE.equals(secureFlagAttempt.get());
            secureFlagAttempt.remove();
            if (!attemptedSecure || window == null) return;

            long mediaAge = System.currentTimeMillis() - lastInstantSeenAt;
            if (currentUrl == null || mediaAge < 0 || mediaAge > INSTANT_MATCH_WINDOW_MS) return;

            // The secure flag is normally applied as the Instants viewer window opens.
            // Wait one frame so its own content is attached, then place our control on
            // that exact window rather than on Instagram's always-present activity.
            MAIN.postDelayed(() -> installInstantControls(window), 80L);
        } catch (Throwable t) {
            Logger.printException(() -> "Instant secure-window hook failed", t);
        }
    }

    public static void noteClearFlag(int flags) {
        secureFlagClear.set((flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }

    public static void noteWindowClearCall(Window window) {
        try {
            boolean clearingSecure = Boolean.TRUE.equals(secureFlagClear.get());
            secureFlagClear.remove();
            if (clearingSecure && window != null) hideInstantControls(window);
        } catch (Throwable t) {
            Logger.printException(() -> "Instant clear-window hook failed", t);
        }
    }

    private static void installInstantControls(Window window) {
        try {
            View decor = window.getDecorView();
            if (!(decor instanceof FrameLayout)) return;

            FrameLayout root = (FrameLayout) decor;
            ImageView button;
            synchronized (buttons) {
                button = buttons.get(root);
                if (button == null) {
                    button = createDownloadButton(root);
                    if (button == null) return;
                    buttons.put(root, button);
                }
                button.setVisibility(View.VISIBLE);
            }

            // The Instants viewer can add its own full-screen child after the media
            // object is created. High Z + bringToFront keeps the button above it.
            button.setElevation(10_000f);
            button.bringToFront();

            final ImageView finalButton = button;
            MAIN.postDelayed(() -> {
                try {
                    if (System.currentTimeMillis() - lastInstantSeenAt >= BUTTON_FALLBACK_HIDE_MS) {
                        finalButton.setVisibility(View.GONE);
                    }
                } catch (Throwable ignored) {
                }
            }, BUTTON_FALLBACK_HIDE_MS);
        } catch (Throwable t) {
            Logger.printException(() -> "Instant controls setup failed", t);
        }
    }

    private static void hideInstantControls(Window window) {
        MAIN.post(() -> {
            try {
                View decor = window.getDecorView();
                synchronized (buttons) {
                    ImageView button = buttons.get(decor);
                    if (button != null) button.setVisibility(View.GONE);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static ImageView createDownloadButton(FrameLayout root) {
        try {
            Context context = root.getContext();
            ImageView button = new ImageView(context);
            UI.setThemedIcon(button, UI.DRAWABLE_DOWNLOAD_ICON);
            button.setContentDescription("Download Instant");
            button.setPadding(Dim.dp12, Dim.dp12, Dim.dp12, Dim.dp12);
            button.setOnClickListener(v -> downloadCurrentInstant(v.getContext()));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Dim.dp16 * 3,
                    Dim.dp16 * 3
            );
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
            lp.topMargin = getStatusBarHeight(context) + (Dim.dp16 / 4);
            lp.rightMargin = Dim.dp16 * 7;

            root.addView(button, lp);
            button.setElevation(10_000f);
            button.bringToFront();
            return button;
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to add Instant download button", t);
            return null;
        }
    }

    private static void downloadCurrentInstant(Context context) {
        try {
            String url = currentUrl;
            String id = currentId;
            if (url == null || id == null || id.isEmpty()) {
                PikoUtils.toast("No Instant media available");
                return;
            }

            String username = currentUsername;
            if (username == null || username.isEmpty()) username = "instagram";

            String safeUsername = username.replaceAll("[^A-Za-z0-9._-]", "_");
            String filename = safeUsername + "_instant_" + id + (currentVideo ? ".mp4" : ".jpg");
            DownloadUtils.downloadMediaUrl(context, url, Constants.DEFAULT_DM_FOLDER, filename);
            PikoUtils.toast("Instant saved: " + filename);
        } catch (Throwable t) {
            Logger.printException(() -> "Instant download failed", t);
        }
    }

    private static int getStatusBarHeight(Context context) {
        try {
            int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
            return id > 0 ? context.getResources().getDimensionPixelSize(id) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private interface StrCall { String get() throws Exception; }
    private interface BoolCall { boolean get() throws Exception; }
    private static String safeStr(StrCall call) { try { return call.get(); } catch (Throwable t) { return null; } }
    private static boolean safeBool(BoolCall call) { try { return call.get(); } catch (Throwable t) { return false; } }
}
