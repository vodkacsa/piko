package app.morphe.extension.instagram.patches.instants;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.PopupWindow;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.patches.download.DownloadUtils;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings("unused")
public final class InstantsDownloadHook {
    private static final long INSTANT_ACTIVE_MS = 45_000L;

    private static volatile String currentId;
    private static volatile String currentUsername;
    private static volatile boolean currentVideo;
    private static volatile String currentUrl;
    private static volatile long lastInstantSeenAt;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile PopupWindow instantPopup;
    private static volatile ViewTreeObserver.OnPreDrawListener secureListener;
    private static volatile View secureListenerRoot;

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

            /*
             * The existing Piko screenshot patch targets Instagram's own screenshot detector
             * and FLAG_SECURE controller. Turn that path on when an Instant is encountered.
             * Our bytecode patch depends on DisableScreenshotDetectionPatch, so the runtime
             * preference is guaranteed to have matching hooks in the patched APK.
             */
            try {
                SharedPref.setBooleanPref(Settings.DISABLE_SCREENSHOT_DETECTION.key, true);
            } catch (Throwable ignored) {
            }

            // The Instant media model is built slightly before the full-screen viewer settles.
            // A PopupWindow is used instead of adding a child to Instagram's activity decor,
            // because the Instants viewer draws a full-screen layer above normal activity views.
            scheduleOverlayInstall(100L);
            scheduleOverlayInstall(300L);
            scheduleOverlayInstall(650L);
            scheduleOverlayInstall(1100L);
            scheduleOverlayInstall(1800L);
        } catch (Throwable t) {
            Logger.printException(() -> "Instant media hook failed", t);
        }
    }

    /** Removes FLAG_SECURE from direct Window flag calls that survived Instagram's controller. */
    public static int stripSecureFlag(int flags) {
        return flags & ~WindowManager.LayoutParams.FLAG_SECURE;
    }

    /** Removes secure SurfaceView / SurfaceControl protection while an Instant is active. */
    public static boolean stripSecureSurface(boolean secure) {
        if (!secure) return false;
        long age = System.currentTimeMillis() - lastInstantSeenAt;
        if (currentUrl != null && age >= 0 && age <= INSTANT_ACTIVE_MS) return false;
        return true;
    }

    private static void scheduleOverlayInstall(long delayMs) {
        MAIN.postDelayed(() -> {
            try {
                if (!isInstantActive()) return;
                Activity activity = findCurrentActivity();
                if (activity != null) {
                    installScreenshotGuard(activity);
                    showInstantPopup(activity);
                }
            } catch (Throwable t) {
                Logger.printException(() -> "Instant overlay retry failed", t);
            }
        }, delayMs);
    }

    private static boolean isInstantActive() {
        long age = System.currentTimeMillis() - lastInstantSeenAt;
        return currentUrl != null && age >= 0 && age <= INSTANT_ACTIVE_MS;
    }

    private static void installScreenshotGuard(Activity activity) {
        try {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            final View decor = activity.getWindow().getDecorView();

            // Instagram may restore the flag after the first frame. Keep clearing it only
            // during the short Instant-viewing window.
            if (secureListenerRoot == decor && secureListener != null) return;
            removeSecureListener();

            final ViewTreeObserver.OnPreDrawListener[] holder = new ViewTreeObserver.OnPreDrawListener[1];
            holder[0] = () -> {
                try {
                    if (isInstantActive()) {
                        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    } else {
                        ViewTreeObserver observer = decor.getViewTreeObserver();
                        if (observer.isAlive()) observer.removeOnPreDrawListener(holder[0]);
                        secureListener = null;
                        secureListenerRoot = null;
                    }
                } catch (Throwable ignored) {
                }
                return true;
            };

            secureListener = holder[0];
            secureListenerRoot = decor;
            decor.getViewTreeObserver().addOnPreDrawListener(holder[0]);
        } catch (Throwable t) {
            Logger.printException(() -> "Instant screenshot guard failed", t);
        }
    }

    private static void removeSecureListener() {
        try {
            View root = secureListenerRoot;
            ViewTreeObserver.OnPreDrawListener listener = secureListener;
            if (root != null && listener != null) {
                ViewTreeObserver observer = root.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnPreDrawListener(listener);
            }
        } catch (Throwable ignored) {
        }
        secureListener = null;
        secureListenerRoot = null;
    }

    private static void showInstantPopup(Activity activity) {
        try {
            PopupWindow old = instantPopup;
            if (old != null && old.isShowing()) {
                // Already on top. Keep its click target/media current instead of stacking windows.
                return;
            }

            Context context = activity;
            ImageView button = new ImageView(context);
            UI.setThemedIcon(button, UI.DRAWABLE_DOWNLOAD_ICON);
            button.setContentDescription("Download Instant");
            button.setPadding(Dim.dp12, Dim.dp12, Dim.dp12, Dim.dp12);
            button.setOnClickListener(v -> downloadCurrentInstant(v.getContext()));

            PopupWindow popup = new PopupWindow(
                    button,
                    Dim.dp16 * 3,
                    Dim.dp16 * 3,
                    false
            );
            popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popup.setOutsideTouchable(false);
            popup.setTouchable(true);
            popup.setClippingEnabled(false);
            popup.setElevation(100_000f);

            View anchor = activity.getWindow().getDecorView();
            int top = getStatusBarHeight(context) + (Dim.dp16 / 4);
            int right = Dim.dp16 * 7;
            popup.showAtLocation(anchor, Gravity.TOP | Gravity.END, right, top);
            instantPopup = popup;

            MAIN.postDelayed(() -> {
                try {
                    if (!isInstantActive() && instantPopup == popup) {
                        popup.dismiss();
                        instantPopup = null;
                    }
                } catch (Throwable ignored) {
                }
            }, INSTANT_ACTIVE_MS + 750L);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to show Instant download popup", t);
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
