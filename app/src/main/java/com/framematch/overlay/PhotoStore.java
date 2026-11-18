package com.framematch.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PhotoStore {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final int MAX_EDGE = 2048;

    private PhotoStore() {}

    private static AtomicFile imageFile(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "reference.webp"));
    }

    static Bitmap load(Context context) {
        try (var input = imageFile(context).openRead()) {
            return BitmapFactory.decodeStream(input);
        } catch (IOException error) {
            return null;
        }
    }

    static Bitmap importPhoto(Context context, Uri source) throws IOException {
        Bitmap bitmap = ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(context.getContentResolver(), source),
                (decoder, info, ignored) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    float scale = Math.min(1f, (float) MAX_EDGE / Math.max(width, height));
                    decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)));
                });
        AtomicFile file = imageFile(context);
        FileOutputStream output = file.startWrite();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 80, output)) {
                throw new IOException("Unable to encode reference photo");
            }
            file.finishWrite(output);
            return bitmap;
        } catch (IOException | RuntimeException error) {
            file.failWrite(output);
            throw error;
        }
    }
}
