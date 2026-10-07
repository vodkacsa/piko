/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.userprofile;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.instagram.entity.ProfileInfo;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.shared.Logger;

/**
 * Ports the personal-fork profile-picture tweaks:
 *
 * 1. Preserve Instagram's normal profile-picture click path.
 * 2. When the native expanded_profile_pic view appears, replace its circular
 *    drawable with the raw backing bitmap and render it FIT_CENTER.
 * 3. If Instagram's native viewer never appears (the broken-profile case),
 *    fall back to Piko's full profile-picture viewer.
 *
 * Nothing consumes the original touch event, so working native behaviour keeps
 * running first.
 */
public final class PfpTweaks {

    private static final String NORMAL_TARGET =
            "row_profile_header_imageview_frame_layout";
    private static final String COIN_FLIP_TARGET =
            "avatar_on_profile_header_view";
    private static final String COIN_FLIP_CLASS =
            "com.instagram.avatars.coinflip.ProfileCoinFlipView";
    private static final String EXPANDED_TARGET =
            "expanded_profile_pic";

    private static final long[] PROBE_DELAYS_MS =
            new long[]{35L, 70L, 110L, 160L, 220L};

    private static final Map<View, Integer> TAP_GENERATIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<ImageView, String> HIGH_RES_REQUESTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private PfpTweaks() {
    }

    public static void bind(ViewGroup profileView, ProfileInfo profileInfo) {
        if (profileView == null || profileInfo == null) return;

        try {
            UserData userData = profileInfo.getUserData();
            View root = profileView.getRootView();
            if (root == null) root = profileView;

            bindAvatarTargets(root, userData, root);

            ImageView expanded = findExpandedProfilePicture(root);
            if (expanded != null) {
                uncropExpandedView(expanded, userData);
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to bind PFP tweaks", t);
        }
    }

    private static void bindAvatarTargets(
            View root,
            UserData userData,
            View searchRoot
    ) {
        for (View target : findAvatarTargets(root)) {
            target.setOnTouchListener((view, event) -> {
                if (event != null
                        && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    scheduleNativeProbe(view, searchRoot, userData);
                }
                return false;
            });
        }
    }

    private static List<View> findAvatarTargets(View root) {
        ArrayList<View> result = new ArrayList<>();
        if (root == null) return result;

        ArrayList<View> queue = new ArrayList<>();
        queue.add(root);

        for (int i = 0; i < queue.size(); i++) {
            View view = queue.get(i);

            if (isAvatarTarget(view)) {
                result.add(view);
            }

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int child = 0; child < group.getChildCount(); child++) {
                    View childView = group.getChildAt(child);
                    if (childView != null) queue.add(childView);
                }
            }
        }

        return result;
    }

    private static boolean isAvatarTarget(View view) {
        if (view == null) return false;

        String id = resourceName(view);
        if (NORMAL_TARGET.equals(id) || COIN_FLIP_TARGET.equals(id)) {
            return true;
        }

        return COIN_FLIP_CLASS.equals(view.getClass().getName());
    }

    private static void scheduleNativeProbe(
            View target,
            View root,
            UserData userData
    ) {
        final int generation;

        synchronized (TAP_GENERATIONS) {
            Integer previous = TAP_GENERATIONS.get(target);
            generation = previous == null ? 1 : previous + 1;
            TAP_GENERATIONS.put(target, generation);
        }

        probe(target, root, userData, generation, 0);
    }

    private static void probe(
            View target,
            View root,
            UserData userData,
            int generation,
            int attempt
    ) {
        if (attempt >= PROBE_DELAYS_MS.length) return;

        target.postDelayed(() -> {
            if (!isCurrentGeneration(target, generation)) return;

            View currentRoot = target.getRootView();
            if (currentRoot == null) currentRoot = root;

            ImageView expanded = findExpandedProfilePicture(currentRoot);
            if (expanded != null) {
                uncropExpandedView(expanded, userData);
                return;
            }

            int nextAttempt = attempt + 1;
            if (nextAttempt < PROBE_DELAYS_MS.length) {
                probe(target, currentRoot, userData, generation, nextAttempt);
                return;
            }

            if (!target.isAttachedToWindow()) return;

            try {
                ProfilePictureViewer.show(target.getContext(), userData);
            } catch (Throwable t) {
                Logger.printException(() -> "Failed to open PFP fallback viewer", t);
            }
        }, PROBE_DELAYS_MS[attempt]);
    }

    private static boolean isCurrentGeneration(View target, int generation) {
        synchronized (TAP_GENERATIONS) {
            Integer current = TAP_GENERATIONS.get(target);
            return current != null && current == generation;
        }
    }

    private static ImageView findExpandedProfilePicture(View root) {
        if (root == null) return null;

        ArrayList<View> queue = new ArrayList<>();
        queue.add(root);

        for (int i = 0; i < queue.size(); i++) {
            View view = queue.get(i);

            if (view instanceof ImageView
                    && EXPANDED_TARGET.equals(resourceName(view))) {
                return (ImageView) view;
            }

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int child = 0; child < group.getChildCount(); child++) {
                    View childView = group.getChildAt(child);
                    if (childView != null) queue.add(childView);
                }
            }
        }

        return null;
    }

    private static void uncropExpandedView(
            ImageView imageView,
            UserData userData
    ) {
        try {
            imageView.setClipToOutline(false);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

            Drawable drawable = imageView.getDrawable();
            Bitmap bitmap = findBestBitmap(drawable);

            if (bitmap != null && !bitmap.isRecycled()) {
                imageView.setImageBitmap(bitmap);
            }

            imageView.invalidate();
            requestHighResolution(imageView, userData);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to uncrop native PFP view", t);
        }
    }

    private static Bitmap findBestBitmap(Object root) {
        if (root == null) return null;

        BitmapCandidate best = new BitmapCandidate();
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        scanForBitmap(root, 0, visited, best);
        return best.bitmap;
    }

    private static void scanForBitmap(
            Object object,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            BitmapCandidate best
    ) {
        if (object == null || depth > 10) return;
        if (visited.put(object, Boolean.TRUE) != null) return;

        if (object instanceof Bitmap) {
            considerBitmap((Bitmap) object, depth, best);
            return;
        }

        if (object instanceof BitmapDrawable) {
            try {
                considerBitmap(((BitmapDrawable) object).getBitmap(), depth + 1, best);
            } catch (Throwable ignored) {
            }
        }

        if (object instanceof Paint) {
            try {
                Shader shader = ((Paint) object).getShader();
                if (shader != null) {
                    scanForBitmap(shader, depth + 1, visited, best);
                }
            } catch (Throwable ignored) {
            }
        }

        Class<?> cls = object.getClass();

        while (cls != null && cls != Object.class) {
            Field[] fields;
            try {
                fields = cls.getDeclaredFields();
            } catch (Throwable ignored) {
                cls = cls.getSuperclass();
                continue;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.isSynthetic()) {
                    continue;
                }

                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(object);
                } catch (Throwable ignored) {
                    continue;
                }

                if (value == null || value == object) continue;

                if (value instanceof Bitmap) {
                    considerBitmap((Bitmap) value, depth + 1, best);
                } else if (shouldTraverse(value)) {
                    scanForBitmap(value, depth + 1, visited, best);
                }
            }

            cls = cls.getSuperclass();
        }
    }

    private static void considerBitmap(
            Bitmap bitmap,
            int depth,
            BitmapCandidate best
    ) {
        if (bitmap == null || bitmap.isRecycled()) return;

        int width;
        int height;
        try {
            width = bitmap.getWidth();
            height = bitmap.getHeight();
        } catch (Throwable ignored) {
            return;
        }

        if (width <= 1 || height <= 1) return;

        long area = (long) width * (long) height;
        long score = area + (long) depth * 1024L;

        if (score > best.score) {
            best.score = score;
            best.bitmap = bitmap;
        }
    }

    private static boolean shouldTraverse(Object value) {
        if (value == null) return false;

        if (value instanceof View
                || value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value.getClass().isEnum()
                || value.getClass().isArray()) {
            return false;
        }

        String name = value.getClass().getName();

        return value instanceof Drawable
                || value instanceof Paint
                || value instanceof Shader
                || name.startsWith("X.")
                || name.startsWith("com.instagram.")
                || name.startsWith("android.graphics.");
    }

    private static void requestHighResolution(
            ImageView imageView,
            UserData userData
    ) {
        if (imageView == null || userData == null) return;

        final String imageUrl;
        try {
            imageUrl = userData.getProfilePictureUrl();
        } catch (Throwable ignored) {
            return;
        }

        if (imageUrl == null || imageUrl.isEmpty()) return;

        synchronized (HIGH_RES_REQUESTS) {
            if (imageUrl.equals(HIGH_RES_REQUESTS.get(imageView))) return;
            HIGH_RES_REQUESTS.put(imageView, imageUrl);
        }

        Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            Bitmap bitmap = null;
            HttpURLConnection connection = null;

            try {
                connection = (HttpURLConnection) new URL(imageUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.connect();

                try (InputStream input = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(input);
                }
            } catch (Throwable t) {
                Logger.printException(() -> "Failed to refresh expanded PFP", t);
            } finally {
                if (connection != null) connection.disconnect();
            }

            Bitmap result = bitmap;
            if (result == null) return;

            mainHandler.post(() -> {
                try {
                    if (!imageView.isAttachedToWindow()) return;
                    if (!EXPANDED_TARGET.equals(resourceName(imageView))) return;

                    imageView.setClipToOutline(false);
                    imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    imageView.setImageBitmap(result);
                    imageView.invalidate();
                } catch (Throwable ignored) {
                }
            });
        }, "Piko-PFP").start();
    }

    private static String resourceName(View view) {
        if (view == null || view.getId() == View.NO_ID) return "";

        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static final class BitmapCandidate {
        Bitmap bitmap;
        long score = Long.MIN_VALUE;
    }
}
