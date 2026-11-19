package pl.pkplk.instructions;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.KeyEvent;
import android.widget.*;
import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle;
import com.github.barteksc.pdfviewer.util.FitPolicy;
import java.io.File;

public class PdfViewerActivity extends Activity {
    private PDFView pdfView;
    private TextView pageCountLabel;
    private EditText pageInput;
    private Button previous, next;
    private ProgressBar loading;
    private int totalPages = 0;
    private boolean documentReady = false;
    private LinearLayout header, pageBar, tools;
    private boolean landscape = false;
    private boolean controlsVisible = true;
    private boolean dark = true;
    private android.content.SharedPreferences readerState;
    private String pageStateKey;
    private int bg, surface, surfaceAlt, text, muted, line, accent, pageCanvas;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideControls = () -> setReaderControlsVisible(false);

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.content.SharedPreferences orientationPrefs = getSharedPreferences("pdf_reader_state", MODE_PRIVATE);
        String savedOrientation = orientationPrefs.getString("screen_orientation", "auto");
        if ("portrait".equals(savedOrientation)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else if ("landscape".equals(savedOrientation)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
        dark = "dark".equals(getSharedPreferences("plk_settings", MODE_PRIVATE).getString("reader_theme", "dark"));
        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        applyPalette();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(12), insets.getSystemWindowInsetTop() + dp(8), dp(12), insets.getSystemWindowInsetBottom() + dp(8));
            return insets;
        });

        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(4), dp(14), dp(4));
        header.setBackground(round(surface, dp(20), line));
        header.setElevation(dp(4));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(32);
        back.setTextColor(text);
        back.setGravity(Gravity.CENTER);
        back.setBackground(round(surfaceAlt, dp(14), line));
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42)));
        back.setOnClickListener(v -> finish());

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(8), 0, 0, 0);
        TextView title = new TextView(this);
        title.setTextColor(text);
        title.setTextSize(14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setMaxLines(1);
        TextView subtitle = new TextView(this);
        subtitle.setText("PKP PLK  •  ПЕРЕГЛЯДАЧ PDF");
        subtitle.setTextColor(muted);
        subtitle.setTextSize(10);
        subtitle.setLetterSpacing(0.08f);
        titleBox.addView(title);
        titleBox.addView(subtitle);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, dp(58));
        headerParams.bottomMargin = dp(12);
        root.addView(header, headerParams);

        pageBar = new LinearLayout(this);
        pageBar.setGravity(Gravity.CENTER);
        pageBar.setPadding(dp(14), dp(5), dp(14), dp(5));
        pageBar.setBackground(round(surface, dp(22), line));
        pageBar.setElevation(dp(5));
        pageCountLabel = new TextView(this);
        pageCountLabel.setText("1  /  —");
        pageCountLabel.setTextColor(text);
        pageCountLabel.setTextSize(12);
        pageCountLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        pageCountLabel.setLetterSpacing(0.04f);
        pageBar.addView(pageCountLabel, new LinearLayout.LayoutParams(-2, -2));
        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setIndeterminate(false);
        loading.setMax(1000);
        loading.setProgress(0);
        loading.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            loading.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(line));
        }
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(3));
        progressParams.bottomMargin = dp(8);
        root.addView(loading, progressParams);

        pdfView = new PDFView(this, null);
        pdfView.setBackgroundColor(pageCanvas);
        root.addView(pdfView, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout.LayoutParams pageBarParams = new LinearLayout.LayoutParams(-2, -2);
        pageBarParams.gravity = Gravity.CENTER_HORIZONTAL;
        pageBarParams.topMargin = dp(8);
        pageBarParams.bottomMargin = dp(10);
        root.addView(pageBar, pageBarParams);

        tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        tools.setPadding(dp(12), dp(8), dp(12), dp(8));
        tools.setBackground(round(surface, dp(26), line));
        tools.setElevation(dp(8));

        Button zoomOut = button("−", false);
        zoomOut.setOnClickListener(v -> zoomBy(0.8f));
        Button fit = button("За шириною", false);
        fit.setTextSize(13);
        fit.setTextColor(accent);
        fit.setOnClickListener(v -> {
            if (documentReady) {
                int currentPage = pdfView.getCurrentPage();
                pdfView.fitToWidth(currentPage);
                setReaderControlsVisible(false);
            }
        });
        Button zoomIn = button("+", false);
        zoomIn.setOnClickListener(v -> zoomBy(1.25f));
        Button rotate = button("↻", false);
        rotate.setTextSize(22);
        rotate.setContentDescription("Повернути та зафіксувати орієнтацію екрана");
        rotate.setOnClickListener(v -> {
            boolean goLandscape = getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE;
            String mode = goLandscape ? "landscape" : "portrait";
            getSharedPreferences("pdf_reader_state", MODE_PRIVATE).edit()
                .putString("screen_orientation", mode).apply();
            setRequestedOrientation(goLandscape
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        });

        tools.addView(zoomOut, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams fitParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        fitParams.setMargins(dp(10), 0, dp(10), 0);
        tools.addView(fit, fitParams);
        tools.addView(zoomIn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams rotateParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        rotateParams.leftMargin = dp(8);
        tools.addView(rotate, rotateParams);
        LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(-1, dp(60));
        toolsParams.topMargin = dp(10);
        tools.setVisibility(View.GONE);

        setContentView(root);
        pdfView.setOnClickListener(v -> toggleReaderControls());
        if (landscape) setReaderControlsVisible(false);

        String path = getIntent().getStringExtra("pdf_path");
        if (path == null || path.trim().isEmpty() || !new File(path).isFile()) {
            loading.setVisibility(View.GONE);
            Toast.makeText(this, "Файл PDF не знайдено", Toast.LENGTH_LONG).show();
            return;
        }
        File file = new File(path);
        readerState = getSharedPreferences("pdf_reader_state", MODE_PRIVATE);
        pageStateKey = "page_" + Integer.toHexString(file.getAbsolutePath().hashCode());
        title.setText(file.getName().replaceAll("(?i)\\.pdf$", ""));

        pdfView.setMinZoom(1f);
        pdfView.setMidZoom(2f);
        pdfView.setMaxZoom(5f);
        pdfView.fromFile(file)
            .enableSwipe(true)
            .swipeHorizontal(false)
            .enableDoubletap(true)
            .defaultPage(0)
            .pageFitPolicy(landscape ? FitPolicy.HEIGHT : FitPolicy.WIDTH)
            .fitEachPage(true)
            .autoSpacing(true)
            .spacing(dp(10))
            .enableAntialiasing(true)
            .scrollHandle(new DefaultScrollHandle(this))
            .onLoad(pages -> {
                totalPages = pages;
                documentReady = true;
                loading.setVisibility(View.VISIBLE);
                loading.setMax(pages);
                pageCountLabel.setText("СТОРІНОК  /  " + pages);
                int savedPage = readerState.getInt(pageStateKey, 0);
                int restorePage = Math.max(0, Math.min(pages - 1, savedPage));
                pdfView.post(() -> {
                    if (restorePage > 0) pdfView.jumpTo(restorePage, false);
                    updatePageControls(restorePage);
                    if (landscape) pdfView.resetZoom();
                });
                setReaderControlsVisible(false);
            })
            .onPageChange((page, pages) -> {
                updatePageControls(page);
                saveReadingPosition(page);
            })
            .onError(error -> {
                loading.setVisibility(View.GONE);
                Toast.makeText(this, "Помилка читання PDF: " +
                    (error.getMessage() == null ? "невідомо" : error.getMessage()),
                    Toast.LENGTH_LONG).show();
            })
            .onPageError((page, error) -> Toast.makeText(this,
                "Не вдалося відобразити сторінку " + (page + 1), Toast.LENGTH_SHORT).show())
            .load();
    }

    private void applyPalette() {
        // AMOLED Glass palette: translucent floating surfaces over a clean canvas.
        if (dark) {
            bg = Color.rgb(4, 5, 9);
            surface = Color.argb(232, 24, 27, 37);
            surfaceAlt = Color.argb(238, 34, 39, 52);
            text = Color.rgb(245, 247, 252);
            muted = Color.rgb(157, 166, 184);
            line = Color.argb(105, 135, 153, 184);
            accent = Color.rgb(112, 181, 255);
            pageCanvas = Color.rgb(5, 6, 10);
        } else {
            bg = Color.rgb(241, 244, 249);
            surface = Color.argb(238, 255, 255, 255);
            surfaceAlt = Color.argb(245, 247, 249, 253);
            text = Color.rgb(27, 34, 48);
            muted = Color.rgb(105, 116, 135);
            line = Color.argb(150, 190, 201, 218);
            accent = Color.rgb(39, 112, 202);
            pageCanvas = Color.rgb(232, 237, 245);
        }
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (!dark && android.os.Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void toggleReaderControls() {
        setReaderControlsVisible(!controlsVisible);
        if (controlsVisible) {
            uiHandler.removeCallbacks(hideControls);
            uiHandler.postDelayed(hideControls, 3500);
        }
    }

    private void setReaderControlsVisible(boolean visible) {
        controlsVisible = visible;
        if (header == null || pageBar == null || tools == null) return;
        int state = visible ? View.VISIBLE : View.GONE;
        header.setVisibility(state);
        pageBar.setVisibility(state);
        tools.setVisibility(state);
        loading.setVisibility(documentReady ? View.GONE : View.VISIBLE);
        if (visible) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                (dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
            );
            uiHandler.removeCallbacks(hideControls);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !controlsVisible) setReaderControlsVisible(false);
    }

    private void saveReadingPosition() {
        if (documentReady && pdfView != null) saveReadingPosition(pdfView.getCurrentPage());
    }

    private void saveReadingPosition(int page) {
        if (readerState != null && pageStateKey != null && page >= 0) {
            readerState.edit().putInt(pageStateKey, page).apply();
        }
    }

    @Override protected void onPause() {
        saveReadingPosition();
        super.onPause();
    }

    @Override protected void onStop() {
        saveReadingPosition();
        super.onStop();
    }

    private void updatePageControls(int page) {
        runOnUiThread(() -> {
            if (pageInput != null) pageInput.setText(String.valueOf(page + 1));
            pageCountLabel.setText((page + 1) + "  /  " + totalPages);
            if (totalPages > 0) loading.setProgress(page + 1);
            if (previous != null) previous.setEnabled(page > 0);
            if (next != null) next.setEnabled(totalPages > 0 && page < totalPages - 1);
            if (previous != null) previous.setAlpha(page > 0 ? 1f : .38f);
            if (next != null) next.setAlpha(totalPages > 0 && page < totalPages - 1 ? 1f : .38f);
        });
    }

    private void jumpToCurrent(int delta) {
        if (!documentReady) return;
        int target = Math.max(0, Math.min(totalPages - 1, pdfView.getCurrentPage() + delta));
        pdfView.jumpTo(target, true);
    }

    private void goToPageFromInput() {
        if (!documentReady) return;
        try {
            int page = Integer.parseInt(pageInput.getText().toString().trim()) - 1;
            if (page >= 0 && page < totalPages) pdfView.jumpTo(page, true);
            else Toast.makeText(this, "Введи номер від 1 до " + totalPages, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            updatePageControls(pdfView.getCurrentPage());
        }
        pageInput.clearFocus();
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(pageInput.getWindowToken(), 0);
    }

    private void zoomBy(float factor) {
        if (!documentReady) return;
        float target = Math.max(1f, Math.min(5f, pdfView.getZoom() * factor));
        pdfView.zoomWithAnimation(target);
    }

    private Button button(String label, boolean compact) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(compact ? 22 : 17);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setTextColor(accent);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(round(surfaceAlt, dp(15), line));
        b.setElevation(dp(1));
        return b;
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onDestroy() {
        uiHandler.removeCallbacks(hideControls);
        if (pdfView != null) pdfView.recycle();
        super.onDestroy();
    }
}
