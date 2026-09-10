package app.morphe.extension.instagram.patches.instants;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
    private static final long INSTANT_ACTIVE_MS = 30_000L;

    private static volatile String currentId;
    private static volatile String currentUsername;
    private static volatile boolean currentVideo;
    private static volatile String currentUrl;
    private static volatile long lastInstantSeenAt;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<View, ImageView> buttons = new WeakHashMap<>();

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

            // The media object is created before the viewer is fully on-screen.
            // Retry for a short period and always bring the overlay to the front.
            scheduleOverlayInstall(180L);
            scheduleOverlayInstall(450L);
            scheduleOverlayInstall(900L);
            scheduleOverlayInstall(1500L);
        } catch (Throwable t) {
            Logger.printException(() -> "Instant media hook failed", t);
        }
    }

    /** Removes FLAG_SECURE from Window flags. */
    public static int stripSecureFlag(int flags) {
        return flags & ~WindowManager.LayoutParams.FLAG_SECURE;
    }

    /**
     * Instagram can protect video through SurfaceView/SurfaceControl rather than Window.FLAG_SECURE.
     * While an Instant is active, force those secure-surface booleans off too.
     */
    public static boolean stripSecureSurface(boolean secure) {
        if (!secure) return false;
        long age = System.currentTimeMillis() - lastInstantSeenAt;
        if (currentUrl != null && age >= 0 && age <= INSTANT_ACTIVE_MS) return false;
        return secure;
    }

    private static void scheduleOverlayInstall(long delayMs) {
        MAIN.postDelayed(() -> {
            try {
                long age = System.currentTimeMillis() - lastInstantSeenAt;
                if (currentUrl == null || age < 0 || age > INSTANT_ACTIVE_MS) return;
                Activity activity = findCurrentActivity();
                if (activity != null) installInstantControls(activity);
            } catch (Throwable t) {
                Logger.printException(() -> "Instant overlay retry failed", t);
            }
        }, delayMs);
    }

    private static void installInstantControls(Activity activity) {
        try {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);

            View decor = activity.getWindow().getDecorView();
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

            button.setElevation(100_000f);
            button.bringToFront();
            root.invalidate();

            final ImageView finalButton = button;
            MAIN.postDelayed(() -> {
                try {
                    if (System.currentTimeMillis() - lastInstantSeenAt > INSTANT_ACTIVE_MS) {
                        finalButton.setVisibility(View.GONE);
                    }
                } catch (Throwable ignored) {
                }
            }, INSTANT_ACTIVE_MS + 500L);
        } catch (Throwable t) {
            Logger.printException(() -> "Instant controls setup failed", t);
        }
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
            button.setElevation(100_000f);
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

    private static Activity findCurrentActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);
            Object activityThread = currentActivityThread.invoke(null);
            if (activityThread == null) return null;

            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object activitiesObject = activitiesField.get(activityThread);
            if (!(activitiesObject instanceof Map)) return null;

            for (Object record : ((Map<?, ?>) activitiesObject).values()) {
                try {
                    Field pausedField = record.getClass().getDeclaredField("paused");
                    pausedField.setAccessible(true);
                    if (pausedField.getBoolean(record)) continue;

                    Field activityField = record.getClass().getDeclaredField("activity");
                    activityField.setAccessible(true);
                    Object activity = activityField.get(record);
                    if (activity instanceof Activity) return (Activity) activity;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private interface StrCall { String get() throws Exception; }
    private interface BoolCall { boolean get() throws Exception; }
    private static String safeStr(StrCall call) { try { return call.get(); } catch (Throwable t) { return null; } }
    private static boolean safeBool(BoolCall call) { try { return call.get(); } catch (Throwable t) { return false; } }
}
