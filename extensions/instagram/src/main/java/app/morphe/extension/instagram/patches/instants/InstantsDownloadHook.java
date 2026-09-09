package app.morphe.extension.instagram.patches.instants;

import android.content.Context;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.patches.download.DownloadUtils;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class InstantsDownloadHook {
    private static volatile String lastId;

    public static void noteInstantMedia(Object media) {
        try {
            if (media == null) return;
            MediaData md = new MediaData(media);
            String id = safeStr(() -> md.getMediaPkId());
            if (id == null || id.isEmpty() || id.equals(lastId)) return;
            lastId = id;

            boolean video = safeBool(() -> md.isVideo());
            String url = video ? safeStr(() -> md.getVideoLink()) : safeStr(() -> md.getImageLink());
            if (url == null || !url.startsWith("http")) return;

            String username = safeStr(() -> md.getUserData().getUsername());
            if (username == null || username.isEmpty()) username = "instagram";
            String filename = username + "_instant_" + id + (video ? ".mp4" : ".jpg");

            Context context = Utils.getContext();
            if (context != null) {
                DownloadUtils.downloadMediaUrl(context, url, Constants.DEFAULT_DM_FOLDER, filename);
                PikoUtils.toast("Instant saved: " + filename);
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Instant download failed", t);
        }
    }

    private interface StrCall { String get() throws Exception; }
    private interface BoolCall { boolean get() throws Exception; }
    private static String safeStr(StrCall call) { try { return call.get(); } catch (Throwable t) { return null; } }
    private static boolean safeBool(BoolCall call) { try { return call.get(); } catch (Throwable t) { return false; } }
}