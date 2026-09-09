package app.morphe.extension.instagram.patches.instants;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
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
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings("unused")
public final class InstantsDownloadHook {
    private static final long BYPASS_TIMEOUT_MS = 10 * 60_000L;

    private static volatile String currentId;
    private static volatile String currentUsername;
    private static volatile boolean currentVideo;
    private static volatile String currentUrl;
    private static volatile long lastInstantSeenAt;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<View, ImageView> buttons = new WeakHashMap<>();
    private static final Map<View, ViewTreeObserver.OnPreDrawListener> secureListeners = new WeakHashMap<>();

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

            installOnCurrentActivityWithRetry(0);
        } catch (Throwable t) {
            Logger.printException(() -> "Instant hook failed", t);
        }
    }

    private static void installOnCurrentActivityWithRetry(int attempt) {
        MAIN.post(() -> {
            try {
                Activity activity = findCurrentActivity();
                if (activity != null) {
                    installInstantControls(activity);
                } else if (attempt < 8) {
                    installOnCurrentActivityWithRetry(attempt + 1);
                }
            } catch (Throwable t) {
                Logger.printException(() -> "Instant controls retry failed", t);
            }
        });
    }

    private static void installInstantControls(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof FrameLayout)) return;

            FrameLayout root = (FrameLayout) decor;
            synchronized (buttons) {
                ImageView button = buttons.get(root);
                if (button == null) {
                    button = createDownloadButton(root);
                    if (button != null) buttons.put(root, button);
                } else {
                    button.setVisibility(View.VISIBLE);
                }
            }

            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            installSecureFlagBypass(activity, root);
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
            // The native grid + camera controls are on the far right.
            // Place the download control immediately to their left.
            lp.rightMargin = Dim.dp16 * 7;

            root.addView(button, lp);
            return button;
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to add Instant download button", t);
            return null;
        }
    }

    private static void installSecureFlagBypass(Activity activity, View root) {
        synchronized (secureListeners) {
            if (secureListeners.containsKey(root)) return;

            final ViewTreeObserver.OnPreDrawListener[] holder = new ViewTreeObserver.OnPreDrawListener[1];
            holder[0] = () -> {
                try {
                    long age = System.currentTimeMillis() - lastInstantSeenAt;
                    if (age <= BYPASS_TIMEOUT_MS) {
                        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                        return true;
                    }

                    ViewTreeObserver vto = root.getViewTreeObserver();
                    if (vto.isAlive()) vto.removeOnPreDrawListener(holder[0]);
                    synchronized (secureListeners) {
                        secureListeners.remove(root);
                    }
                } catch (Throwable ignored) {
                    // Do not break Instagram rendering if the window implementation changes.
                }
                return true;
            };

            secureListeners.put(root, holder[0]);
            root.getViewTreeObserver().addOnPreDrawListener(holder[0]);
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
                    // Try the next record.
                }
            }
        } catch (Throwable ignored) {
            // Hidden API access can be restricted on some Android builds.
        }
        return null;
    }

    private interface StrCall { String get() throws Exception; }
    private interface BoolCall { boolean get() throws Exception; }
    private static String safeStr(StrCall call) { try { return call.get(); } catch (Throwable t) { return null; } }
    private static boolean safeBool(BoolCall call) { try { return call.get(); } catch (Throwable t) { return false; } }
}
