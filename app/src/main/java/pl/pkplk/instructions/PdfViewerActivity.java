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
import android.view.ScaleGestureDetector;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import android.view.View;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfViewerActivity extends Activity {
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private ZoomableImageView pageImage;
    private TextView pageTotal;
    private EditText pageNumber;
    private Button prevButton, nextButton;
    private ProgressBar progress;
    private int pageIndex = 0;
    private int pageCount = 0;
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
        bar.setPadding(8, 5, 8, 5);

        prevButton = new Button(this);
        prevButton.setText("‹");
        prevButton.setTextSize(24);
        prevButton.setContentDescription("Попередня сторінка");
        prevButton.setOnClickListener(v -> showPage(pageIndex - 1));

        pageNumber = new EditText(this);
        pageNumber.setSingleLine(true);
        pageNumber.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        pageNumber.setImeOptions(EditorInfo.IME_ACTION_GO);
        pageNumber.setTextColor(Color.WHITE);
        pageNumber.setHintTextColor(Color.LTGRAY);
        pageNumber.setHint("№");
        pageNumber.setGravity(Gravity.CENTER);
        pageNumber.setTextSize(16);
        pageNumber.setSelectAllOnFocus(true);
        pageNumber.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                goToEnteredPage();
                return true;
            }
            return false;
        });

        pageTotal = new TextView(this);
        pageTotal.setTextColor(Color.WHITE);
        pageTotal.setGravity(Gravity.CENTER_VERTICAL);
        pageTotal.setTextSize(15);
        pageTotal.setPadding(4, 0, 8, 0);

        nextButton = new Button(this);
        nextButton.setText("›");
        nextButton.setTextSize(24);
        nextButton.setContentDescription("Наступна сторінка");
        nextButton.setOnClickListener(v -> showPage(pageIndex + 1));

        bar.addView(prevButton, new LinearLayout.LayoutParams(52, 48));
        bar.addView(pageNumber, new LinearLayout.LayoutParams(54, 48));
        bar.addView(pageTotal, new LinearLayout.LayoutParams(0, 48, 1));
        bar.addView(nextButton, new LinearLayout.LayoutParams(52, 48));
        root.addView(bar);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(-1, 4));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        pageImage = new ZoomableImageView(this);
        pageImage.setAdjustViewBounds(true);
        pageImage.setScaleType(ImageView.ScaleType.MATRIX);
        pageImage.setBackgroundColor(Color.WHITE);
        pageImage.setPageSwipeListener(direction -> showPage(pageIndex + direction));
        scroll.addView(pageImage, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout zoomBar = new LinearLayout(this);
        zoomBar.setGravity(Gravity.CENTER);
        zoomBar.setPadding(6, 3, 6, 3);
        Button zoomOut = new Button(this);
        zoomOut.setText("−");
        zoomOut.setTextSize(20);
        zoomOut.setContentDescription("Зменшити масштаб");
        zoomOut.setOnClickListener(v -> pageImage.zoomBy(0.8f));
        Button fit = new Button(this);
        fit.setText("Dopasuj");
        fit.setTextSize(13);
        fit.setOnClickListener(v -> pageImage.fitToWidth());
        Button zoomIn = new Button(this);
        zoomIn.setText("+");
        zoomIn.setTextSize(20);
        zoomIn.setContentDescription("Збільшити масштаб");
        zoomIn.setOnClickListener(v -> pageImage.zoomBy(1.25f));
        zoomBar.addView(zoomOut, new LinearLayout.LayoutParams(64, 46));
        zoomBar.addView(fit, new LinearLayout.LayoutParams(112, 46));
        zoomBar.addView(zoomIn, new LinearLayout.LayoutParams(64, 46));
        root.addView(zoomBar);

        setContentView(root);
        try {
            String path = getIntent().getStringExtra("pdf_path");
            if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("Missing PDF path");
            File f = new File(path);
            descriptor = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
            pageCount = renderer.getPageCount();
            pageTotal.setText("/ " + pageCount);
            updateNavigation();
            showPage(0);
        } catch (Exception e) {
            progress.setVisibility(View.GONE);
            pageTotal.setText("Не вдалося відкрити PDF");
            Toast.makeText(this, "Помилка відкриття документа PDF", Toast.LENGTH_LONG).show();
        }
    }

    private void goToEnteredPage() {
        String value = pageNumber.getText().toString().trim();
        if (value.isEmpty()) {
            pageNumber.setText(String.valueOf(pageIndex + 1));
            return;
        }
        try {
            int requested = Integer.parseInt(value);
            showPage(requested - 1);
        } catch (NumberFormatException ignored) {
            pageNumber.setText(String.valueOf(pageIndex + 1));
        }
        pageNumber.clearFocus();
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(pageNumber.getWindowToken(), 0);
    }

    private void updateNavigation() {
        if (pageNumber != null) pageNumber.setText(String.valueOf(pageIndex + 1));
        if (prevButton != null) prevButton.setEnabled(pageIndex > 0);
        if (nextButton != null) nextButton.setEnabled(pageIndex < pageCount - 1);
    }

    private void showPage(int index) {
        if (renderer == null || index < 0 || index >= pageCount) {
            if (pageNumber != null) pageNumber.setText(String.valueOf(pageIndex + 1));
            return;
        }
        pageIndex = index;
        updateNavigation();
        final int thisRequest = ++requestId;
        final int count = pageCount;
        pageTotal.setText("/ " + count);
        progress.setVisibility(View.VISIBLE);
        renderQueue.execute(() -> {
            Bitmap bitmap = null;
            try (PdfRenderer.Page page = renderer.openPage(index)) {
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int width = Math.max(900, Math.min(2200, screenWidth * 2));
                float ratio = page.getHeight() / (float) page.getWidth();
                int height = Math.max(1, Math.min(3000, (int) (width * ratio)));
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
                    pageNumber.setText(String.valueOf(index + 1));
                    pageTotal.setText("/ " + count);
                    progress.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                main.post(() -> {
                    if (thisRequest == requestId) {
                        pageTotal.setText("/ " + count + " • помилка");
                        progress.setVisibility(View.GONE);
                        Toast.makeText(this, "Не вдалося відобразити сторінку PDF", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private static class ZoomableImageView extends ImageView {
        interface PageSwipeListener { void onSwipe(int direction); }
        private final android.graphics.Matrix imageMatrix = new android.graphics.Matrix();
        private final ScaleGestureDetector scaleDetector;
        private float lastX, lastY, downX, downY;
        private float baseScale = 1f;
        private float currentScale = 1f;
        private boolean horizontalGesture = false;
        private PageSwipeListener pageSwipeListener;

        ZoomableImageView(Activity activity) {
            super(activity);
            setScaleType(ScaleType.MATRIX);
            scaleDetector = new ScaleGestureDetector(activity, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    float next = Math.max(baseScale, Math.min(baseScale * 5f, currentScale * detector.getScaleFactor()));
                    float applied = next / currentScale;
                    imageMatrix.postScale(applied, applied, detector.getFocusX(), detector.getFocusY());
                    currentScale = next;
                    setImageMatrix(imageMatrix);
                    return true;
                }
            });
        }

        void setPageSwipeListener(PageSwipeListener listener) { pageSwipeListener = listener; }

        void zoomBy(float factor) {
            if (getDrawable() == null) return;
            float next = Math.max(baseScale, Math.min(baseScale * 5f, currentScale * factor));
            float applied = next / currentScale;
            imageMatrix.postScale(applied, applied, getWidth() / 2f, getHeight() / 2f);
            currentScale = next;
            setImageMatrix(imageMatrix);
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
                setImageMatrix(imageMatrix);
                scrollTo(0, 0);
            });
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                horizontalGesture = false;
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_POINTER_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_MOVE) {
                if (scaleDetector.isInProgress()) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                if (currentScale > baseScale * 1.01f && event.getPointerCount() == 1) {
                    float x = event.getX(), y = event.getY();
                    imageMatrix.postTranslate(x - lastX, y - lastY);
                    setImageMatrix(imageMatrix);
                    lastX = x;
                    lastY = y;
                    getParent().requestDisallowInterceptTouchEvent(true);
                } else if (event.getPointerCount() == 1) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > 18 && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                        horizontalGesture = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_UP) {
                if (horizontalGesture && currentScale <= baseScale * 1.01f) {
                    float dx = event.getX() - downX;
                    if (Math.abs(dx) > 65 && pageSwipeListener != null) {
                        pageSwipeListener.onSwipe(dx < 0 ? 1 : -1);
                    }
                }
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_CANCEL || action == android.view.MotionEvent.ACTION_POINTER_UP) {
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
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
