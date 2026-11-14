package pl.pkplk.instructions;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.widget.*;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfViewerActivity extends Activity {
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private ZoomableImageView pageImage;
    private TextView pageLabel;
    private ProgressBar progress;
    private int pageIndex = 0;
    private int requestId = 0;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService renderQueue = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(28, 38, 50));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(10, 6, 10, 6);
        Button prev = new Button(this);
        prev.setText("‹");
        prev.setOnClickListener(v -> showPage(pageIndex - 1));
        Button next = new Button(this);
        next.setText("›");
        next.setOnClickListener(v -> showPage(pageIndex + 1));
        pageLabel = new TextView(this);
        pageLabel.setTextColor(Color.WHITE);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setTextSize(14);
        bar.addView(prev, new LinearLayout.LayoutParams(56, 48));
        bar.addView(pageLabel, new LinearLayout.LayoutParams(0, 48, 1));
        bar.addView(next, new LinearLayout.LayoutParams(56, 48));
        root.addView(bar);
        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(-1, 4));
        ScrollView scroll = new ScrollView(this);
        pageImage = new ZoomableImageView(this);
        pageImage.setAdjustViewBounds(true);
        pageImage.setScaleType(ImageView.ScaleType.MATRIX);
        scroll.addView(pageImage, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        try {
            File f = new File(getIntent().getStringExtra("pdf_path"));
            descriptor = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
            showPage(0);
        } catch (Exception e) {
            progress.setVisibility(android.view.View.GONE);
            pageLabel.setText("Nie można otworzyć PDF");
            Toast.makeText(this, "Błąd otwierania dokumentu", Toast.LENGTH_LONG).show();
        }
    }

    private void showPage(int index) {
        if (renderer == null || index < 0 || index >= renderer.getPageCount()) return;
        pageIndex = index;
        final int thisRequest = ++requestId;
        final int count = renderer.getPageCount();
        pageLabel.setText("Ładowanie " + (index + 1) + " / " + count);
        progress.setVisibility(android.view.View.VISIBLE);
        renderQueue.execute(() -> {
            Bitmap bitmap = null;
            try (PdfRenderer.Page page = renderer.openPage(index)) {
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int width = Math.max(700, Math.min(1800, screenWidth * 2));
                float ratio = page.getHeight() / (float) page.getWidth();
                int height = Math.max(1, Math.min(2400, (int) (width * ratio)));
                if ((long) width * height > 8_000_000L) height = Math.max(1, 8_000_000 / width);
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                Bitmap ready = bitmap;
                main.post(() -> {
                    if (isFinishing() || thisRequest != requestId) {
                        ready.recycle();
                        return;
                    }
                    Bitmap old = pageImage.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                        ? ((android.graphics.drawable.BitmapDrawable) pageImage.getDrawable()).getBitmap() : null;
                    pageImage.setImageBitmap(ready);
                    pageImage.fitToWidth();
                    if (old != null && old != ready && !old.isRecycled()) old.recycle();
                    pageLabel.setText((index + 1) + " / " + count);
                    progress.setVisibility(android.view.View.GONE);
                });
            } catch (Exception e) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                main.post(() -> {
                    if (thisRequest == requestId) {
                        pageLabel.setText("Błąd strony " + (index + 1));
                        progress.setVisibility(android.view.View.GONE);
                        Toast.makeText(this, "Nie udało się wyrenderować strony PDF", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private static class ZoomableImageView extends ImageView {
        private final android.graphics.Matrix imageMatrix = new android.graphics.Matrix();
        private final ScaleGestureDetector scaleDetector;
        private float lastX, lastY;
        private float baseScale = 1f;
        private float currentScale = 1f;
        private boolean scaling = false;

        ZoomableImageView(Activity activity) {
            super(activity);
            scaleDetector = new ScaleGestureDetector(activity, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                    scaling = true;
                    return true;
                }
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    float factor = detector.getScaleFactor();
                    float next = Math.max(baseScale, Math.min(baseScale * 5f, currentScale * factor));
                    float applied = next / currentScale;
                    imageMatrix.postScale(applied, applied, detector.getFocusX(), detector.getFocusY());
                    currentScale = next;
                    setImageMatrix(imageMatrix);
                    return true;
                }
                @Override public void onScaleEnd(ScaleGestureDetector detector) {
                    scaling = false;
                }
            });
        }

        void fitToWidth() {
            post(() -> {
                android.graphics.drawable.Drawable drawable = getDrawable();
                if (drawable == null || getWidth() <= 0) return;
                float imageWidth = drawable.getIntrinsicWidth();
                if (imageWidth <= 0) return;
                baseScale = getWidth() / imageWidth;
                currentScale = baseScale;
                imageMatrix.reset();
                imageMatrix.setScale(baseScale, baseScale);
                float drawnWidth = imageWidth * baseScale;
                imageMatrix.postTranslate(Math.max(0, (getWidth() - drawnWidth) / 2f), 0);
                setImageMatrix(imageMatrix);
            });
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                lastX = event.getX();
                lastY = event.getY();
                getParent().requestDisallowInterceptTouchEvent(currentScale > baseScale * 1.01f);
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_POINTER_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_MOVE) {
                if (!scaleDetector.isInProgress() && event.getPointerCount() == 1
                        && currentScale > baseScale * 1.01f) {
                    float x = event.getX();
                    float y = event.getY();
                    imageMatrix.postTranslate(x - lastX, y - lastY);
                    setImageMatrix(imageMatrix);
                    lastX = x;
                    lastY = y;
                } else if (event.getPointerCount() == 1) {
                    lastX = event.getX();
                    lastY = event.getY();
                }
                getParent().requestDisallowInterceptTouchEvent(currentScale > baseScale * 1.01f);
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_POINTER_UP || action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                scaling = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            return true;
        }
    }

    @Override protected void onDestroy() {
        requestId++;
        renderQueue.shutdownNow();
        try {
            if (renderer != null) renderer.close();
            if (descriptor != null) descriptor.close();
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
