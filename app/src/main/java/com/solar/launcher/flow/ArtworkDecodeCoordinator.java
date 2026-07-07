package com.solar.launcher.flow;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.widget.ImageView;

import com.solar.launcher.R;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-wide serialized disk decode queue with stale-target protection. */
public final class ArtworkDecodeCoordinator {
    private static final int MSG_EXACT = 1;
    private static final int MSG_PHOTO = 2;
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final HandlerThread THREAD;
    private static final Handler WORKER;

    static {
        THREAD = new HandlerThread("solar-art", Process.THREAD_PRIORITY_BACKGROUND);
        THREAD.start();
        WORKER = new Handler(THREAD.getLooper()) {
            @Override public void handleMessage(Message message) {
                Request request = (Request) message.obj;
                if (message.what == MSG_EXACT) request.lease = ArtworkBitmapPool.decodeExact(
                        request.file, request.width, request.height);
                else request.lease = ArtworkBitmapPool.Lease.unpooled(
                        decodePhoto(request.file, request.width));
                MAIN.post(request);
            }
        };
    }

    private ArtworkDecodeCoordinator() {}

    public static void loadExact(ImageView target, File file, int width, int height, boolean visible) {
        enqueue(target, file, width, height, visible, MSG_EXACT);
    }

    public static void loadPhoto(ImageView target, File file, int maxSidePx) {
        enqueue(target, file, maxSidePx, 0, true, MSG_PHOTO);
    }

    public static void cancel(ImageView target) {
        if (target != null) target.setTag(R.id.tag_artwork_request, null);
    }

    public static void clear(ImageView target) {
        if (target == null) return;
        cancel(target);
        Object lease = target.getTag(R.id.tag_artwork_bitmap_lease);
        if (lease instanceof ArtworkBitmapPool.Lease) {
            ((ArtworkBitmapPool.Lease) lease).release();
            target.setTag(R.id.tag_artwork_bitmap_lease, null);
        }
        target.setImageDrawable(null);
    }

    private static void enqueue(ImageView target, File file, int width, int height,
            boolean visible, int what) {
        if (target == null || file == null || width <= 0) return;
        Request request = new Request(IDS.incrementAndGet(), target, file, width, height);
        target.setTag(R.id.tag_artwork_request, request.id);
        Message message = Message.obtain(WORKER, what, request);
        if (visible) WORKER.sendMessageAtFrontOfQueue(message); else message.sendToTarget();
    }

    private static Bitmap decodePhoto(File file, int maxSidePx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxSidePx) sample <<= 1;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static final class Request implements Runnable {
        final Integer id;
        final ImageView target;
        final File file;
        final int width;
        final int height;
        ArtworkBitmapPool.Lease lease;

        Request(int id, ImageView target, File file, int width, int height) {
            this.id = id;
            this.target = target;
            this.file = file;
            this.width = width;
            this.height = height;
        }

        @Override public void run() {
            Object token = target.getTag(R.id.tag_artwork_request);
            if (!id.equals(token)) {
                if (lease != null) lease.release();
                return;
            }
            Object old = target.getTag(R.id.tag_artwork_bitmap_lease);
            if (old instanceof ArtworkBitmapPool.Lease) ((ArtworkBitmapPool.Lease) old).release();
            target.setTag(R.id.tag_artwork_bitmap_lease, lease);
            target.setImageBitmap(lease != null ? lease.bitmap() : null);
        }
    }
}
