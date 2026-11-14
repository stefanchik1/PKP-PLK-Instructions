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
import android.view.ViewGroup;
import android.graphics.drawable.GradientDrawable;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfViewerActivity extends Activity {
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private ScrollView scroll;
    private LinearLayout pagesColumn;
    private TextView pageTotal, titleView;
    private EditText pageNumber;
    private Button prevButton, nextButton;
    private ProgressBar progress;
    private int pageIndex = 0, pageCount = 0;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService renderQueue = Executors.newSingleThreadExecutor();
    private final Set<Integer> rendering = new HashSet<>();
    private final ImageView[] pageViews = new ImageView[0];
    private ImageView[] images;
    private TextView[] pageLabels;
    private Bitmap[] bitmaps;
    private boolean closed = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(15, 25, 38));
        getWindow().setNavigationBarColor(Color.rgb(15, 25, 38));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(238, 242, 247));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(8), dp(12), dp(8));
        toolbar.setBackgroundColor(Color.rgb(15, 25, 38));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(Color.WHITE);
        back.setTextSize(34);
        back.setGravity(Gravity.CENTER);
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        back.setOnClickListener(v -> finish());
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(8), 0, 0, 0);
        titleView = new TextView(this);
        titleView.setText("Перегляд PDF");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(16);
        titleView.setMaxLines(1);
        TextView subtitle = new TextView(this);
        subtitle.setText("Документ · режим читання");
        subtitle.setTextColor(Color.rgb(174, 190, 208));
        subtitle.setTextSize(11);
        heading.addView(titleView);
        heading.addView(subtitle);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolbar);

        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setPadding(dp(12), dp(7), dp(12), dp(7));
        navigation.setBackgroundColor(Color.WHITE);
        prevButton = navButton("‹");
        prevButton.setOnClickListener(v -> goToPage(pageIndex - 1));
        navigation.addView(prevButton, new LinearLayout.LayoutParams(dp(44), dp(42)));
        pageNumber = new EditText(this);
        pageNumber.setSingleLine(true);
        pageNumber.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        pageNumber.setImeOptions(EditorInfo.IME_ACTION_GO);
        pageNumber.setTextColor(Color.rgb(24, 43, 62));
        pageNumber.setTextSize(15);
        pageNumber.setGravity(Gravity.CENTER);
        pageNumber.setSelectAllOnFocus(true);
        pageNumber.setBackground(round(Color.rgb(240, 244, 248), dp(9)));
        pageNumber.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                try { goToPage(Integer.parseInt(pageNumber.getText().toString()) - 1); }
                catch (Exception ignored) { updateNavigation(); }
                ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(pageNumber.getWindowToken(), 0);
                pageNumber.clearFocus();
                return true;
            }
            return false;
        });
        navigation.addView(pageNumber, new LinearLayout.LayoutParams(dp(54), dp(42)));
        pageTotal = new TextView(this);
        pageTotal.setTextColor(Color.rgb(80, 98, 118));
        pageTotal.setTextSize(14);
        pageTotal.setPadding(dp(8), 0, 0, 0);
        navigation.addView(pageTotal, new LinearLayout.LayoutParams(0, -2, 1));
        nextButton = navButton("›");
        nextButton.setOnClickListener(v -> goToPage(pageIndex + 1));
        navigation.addView(nextButton, new LinearLayout.LayoutParams(dp(44), dp(42)));
        root.addView(navigation);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(8), dp(12), dp(8), dp(18));
        pagesColumn = new LinearLayout(this);
        pagesColumn.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(pagesColumn, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> {
            updateVisiblePage();
            renderNearbyPages();
        });

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        tools.setPadding(dp(10), dp(5), dp(10), dp(5));
        tools.setBackgroundColor(Color.WHITE);
        Button minus = toolButton("−");
        minus.setOnClickListener(v -> zoomVisible(0.85f));
        Button fit = toolButton("За шириною");
        fit.setOnClickListener(v -> fitVisible());
        Button plus = toolButton("+");
        plus.setOnClickListener(v -> zoomVisible(1.2f));
        tools.addView(minus, new LinearLayout.LayoutParams(dp(58), dp(44)));
        LinearLayout.LayoutParams fitParams = new LinearLayout.LayoutParams(dp(150), dp(44));
        fitParams.setMargins(dp(10), 0, dp(10), 0);
        tools.addView(fit, fitParams);
        tools.addView(plus, new LinearLayout.LayoutParams(dp(58), dp(44)));
        root.addView(tools);
        setContentView(root);

        try {
            String path = getIntent().getStringExtra("pdf_path");
            if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("Missing PDF path");
            File f = new File(path);
            titleView.setText(f.getName().replaceAll("(?i)\\.pdf$", ""));
            descriptor = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
            pageCount = renderer.getPageCount();
            images = new ImageView[pageCount];
            pageLabels = new TextView[pageCount];
            bitmaps = new Bitmap[pageCount];
            pageTotal.setText("/ " + pageCount + " сторінок");
            for (int i = 0; i < pageCount; i++) addPagePlaceholder(i);
            updateNavigation();
            scroll.post(this::renderNearbyPages);
        } catch (Exception e) {
            pageTotal.setText("Помилка відкриття документа");
            Toast.makeText(this, "Не вдалося відкрити PDF", Toast.LENGTH_LONG).show();
        }
    }

    private void addPagePlaceholder(int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(Color.WHITE, dp(5)));
        card.setElevation(dp(2));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.setMargins(dp(2), 0, dp(2), dp(14));
        pagesColumn.addView(card, cardParams);
        TextView label = new TextView(this);
        label.setText("СТОРІНКА " + (index + 1));
        label.setTextColor(Color.rgb(115, 130, 146));
        label.setTextSize(10);
        label.setLetterSpacing(0.08f);
        label.setPadding(dp(12), dp(8), dp(12), dp(5));
        card.addView(label);
        pageLabels[index] = label;
        TouchZoomImageView image = new TouchZoomImageView(this);
        image.setBackgroundColor(Color.WHITE);
        image.setScaleType(ImageView.ScaleType.MATRIX);
        image.setAdjustViewBounds(true);
        image.setContentDescription("Сторінка " + (index + 1) + " PDF");
        int approximateHeight = (int)(getResources().getDisplayMetrics().widthPixels * 1.35f);
        card.addView(image, new LinearLayout.LayoutParams(-1, approximateHeight));
        images[index] = image;
    }

    private void renderNearbyPages() {
        if (renderer == null || closed || images == null) return;
        int first = 0, last = pageCount - 1;
        for (int i = 0; i < pageCount; i++) {
            View page = (View) images[i].getParent();
            int top = page.getTop() - scroll.getScrollY();
            int bottom = top + page.getHeight();
            if (bottom >= -scroll.getHeight() && top <= scroll.getHeight() * 2) {
                first = Math.max(0, i - 1);
                last = Math.min(pageCount - 1, i + 2);
                break;
            }
        }
        for (int i = first; i <= last; i++) renderPage(i);
        int keepFrom = Math.max(0, first - 2), keepTo = Math.min(pageCount - 1, last + 2);
        for (int i = 0; i < pageCount; i++) {
            if ((i < keepFrom || i > keepTo) && bitmaps[i] != null) {
                Bitmap old = bitmaps[i]; bitmaps[i] = null;
                images[i].setImageDrawable(null);
                if (!old.isRecycled()) old.recycle();
            }
        }
    }

    private void renderPage(int index) {
        if (bitmaps[index] != null || rendering.contains(index)) return;
        rendering.add(index);
        if (progress.getVisibility() != View.VISIBLE) progress.setVisibility(View.VISIBLE);
        renderQueue.execute(() -> {
            Bitmap bitmap = null;
            try (PdfRenderer.Page page = renderer.openPage(index)) {
                int width = Math.min(1800, Math.max(1000, getResources().getDisplayMetrics().widthPixels * 2));
                float ratio = page.getHeight() / (float) page.getWidth();
                int height = Math.max(1, (int)(width * ratio));
                if ((long)width * height > 5_000_000L) height = Math.max(1, 5_000_000 / width);
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                Bitmap ready = bitmap;
                main.post(() -> {
                    rendering.remove(index);
                    if (closed || isFinishing()) { ready.recycle(); return; }
                    bitmaps[index] = ready;
                    images[index].setImageBitmap(ready);
                    images[index].setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
                    images[index].fitToWidth();
                    pageLabels[index].setText("СТОРІНКА " + (index + 1));
                    if (rendering.isEmpty()) progress.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                main.post(() -> {
                    rendering.remove(index);
                    if (rendering.isEmpty()) progress.setVisibility(View.GONE);
                    if (!closed) pageLabels[index].setText("Не вдалося завантажити сторінку " + (index + 1));
                });
            }
        });
    }

    private void updateVisiblePage() {
        if (images == null) return;
        int best = pageIndex;
        int center = scroll.getScrollY() + scroll.getHeight() / 3;
        for (int i = 0; i < pageCount; i++) {
            View page = (View) images[i].getParent();
            if (page.getTop() <= center && page.getBottom() >= center) { best = i; break; }
        }
        if (best != pageIndex) {
            pageIndex = best;
            updateNavigation();
        }
    }

    private void goToPage(int index) {
        if (index < 0 || index >= pageCount || images == null) return;
        pageIndex = index;
        updateNavigation();
        View page = (View) images[index].getParent();
        scroll.smoothScrollTo(0, page.getTop());
        renderNearbyPages();
    }

    private void updateNavigation() {
        if (pageNumber != null) pageNumber.setText(String.valueOf(pageIndex + 1));
        if (prevButton != null) prevButton.setEnabled(pageIndex > 0);
        if (nextButton != null) nextButton.setEnabled(pageIndex < pageCount - 1);
    }

    private void zoomVisible(float factor) {
        if (images != null && pageIndex >= 0 && pageIndex < images.length) images[pageIndex].zoomBy(factor);
    }
    private void fitVisible() {
        if (images != null) for (int i = 0; i < images.length; i++) if (bitmaps[i] != null) images[i].fitToWidth();
    }

    private Button navButton(String text) {
        Button b = new Button(this);
        b.setText(text); b.setTextSize(23); b.setTextColor(Color.rgb(27, 54, 78));
        b.setBackground(round(Color.rgb(239, 244, 248), dp(11)));
        b.setPadding(0, 0, 0, 0);
        return b;
    }
    private Button toolButton(String text) {
        Button b = new Button(this);
        b.setText(text); b.setTextSize(14); b.setTextColor(Color.rgb(27, 54, 78));
        b.setAllCaps(false); b.setBackground(round(Color.rgb(239, 244, 248), dp(12)));
        return b;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(radius); return d;
    }
    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }

    private static class TouchZoomImageView extends ImageView {
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();
        private final ScaleGestureDetector detector;
        private float base = 1f, scale = 1f, lastX, lastY;
        TouchZoomImageView(Activity activity) {
            super(activity);
            detector = new ScaleGestureDetector(activity, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    float next = Math.max(base, Math.min(base * 4f, scale * d.getScaleFactor()));
                    matrix.postScale(next / scale, next / scale, d.getFocusX(), d.getFocusY());
                    scale = next; setImageMatrix(matrix); return true;
                }
            });
        }
        void fitToWidth() {
            post(() -> {
                if (getDrawable() == null || getWidth() == 0) return;
                base = getWidth() / (float)getDrawable().getIntrinsicWidth();
                scale = base; matrix.reset(); matrix.setScale(base, base); setImageMatrix(matrix);
            });
        }
        void zoomBy(float factor) {
            if (getDrawable() == null) return;
            float next = Math.max(base, Math.min(base * 4f, scale * factor));
            matrix.postScale(next / scale, next / scale, getWidth()/2f, getHeight()/2f);
            scale = next; setImageMatrix(matrix);
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            detector.onTouchEvent(e);
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) { lastX=e.getX(); lastY=e.getY(); return true; }
            if (a == android.view.MotionEvent.ACTION_MOVE) {
                if (detector.isInProgress() || scale > base * 1.01f) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    if (!detector.isInProgress() && e.getPointerCount()==1) {
                        matrix.postTranslate(e.getX()-lastX,e.getY()-lastY); setImageMatrix(matrix);
                    }
                } else getParent().requestDisallowInterceptTouchEvent(false);
                lastX=e.getX(); lastY=e.getY(); return true;
            }
            if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false); performClick(); return true;
            }
            if (a == android.view.MotionEvent.ACTION_POINTER_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }

    @Override protected void onDestroy() {
        closed = true;
        renderQueue.shutdownNow();
        if (bitmaps != null) for (Bitmap b : bitmaps) if (b != null && !b.isRecycled()) b.recycle();
        try { if (renderer != null) renderer.close(); if (descriptor != null) descriptor.close(); }
        catch (Exception ignored) {}
        super.onDestroy();
    }
}
