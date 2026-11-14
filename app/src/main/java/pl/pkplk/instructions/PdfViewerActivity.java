package pl.pkplk.instructions;

import android.app.Activity;
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
    private int bg, surface, surfaceAlt, text, muted, line, accent, pageCanvas;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideControls = () -> setReaderControlsVisible(false);

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dark = true;
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
        subtitle.setText("PDF  /  PKP PLK");
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
        pageBar.setGravity(Gravity.CENTER_VERTICAL);
        pageBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        pageBar.setBackground(round(surface, dp(24), line));
        pageBar.setElevation(dp(3));

        previous = button("‹", true);
        previous.setOnClickListener(v -> jumpToCurrent(-1));
        pageBar.addView(previous, new LinearLayout.LayoutParams(dp(40), dp(40)));

        pageInput = new EditText(this);
        pageInput.setSingleLine(true);
        pageInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        pageInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        pageInput.setTextColor(text);
        pageInput.setTextSize(14);
        pageInput.setTypeface(null, android.graphics.Typeface.BOLD);
        pageInput.setGravity(Gravity.CENTER);
        pageInput.setSelectAllOnFocus(true);
        pageInput.setBackground(round(surfaceAlt, dp(13), line));
        pageInput.setHintTextColor(muted);
        pageInput.setHint("1");
        pageInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                goToPageFromInput();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(dp(52), dp(40));
        inputParams.setMargins(dp(8), 0, dp(7), 0);
        pageBar.addView(pageInput, inputParams);

        pageCountLabel = new TextView(this);
        pageCountLabel.setText("СТОРІНКА  /  —");
        pageCountLabel.setTextColor(muted);
        pageCountLabel.setTextSize(11);
        pageCountLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        pageBar.addView(pageCountLabel, new LinearLayout.LayoutParams(0, -2, 1));

        next = button("›", true);
        next.setOnClickListener(v -> jumpToCurrent(1));
        pageBar.addView(next, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout.LayoutParams pageBarParams = new LinearLayout.LayoutParams(-1, dp(56));
        pageBarParams.bottomMargin = dp(10);
        root.addView(pageBar, pageBarParams);

        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setIndeterminate(true);
        loading.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        root.addView(loading, new LinearLayout.LayoutParams(-1, dp(3)));

        pdfView = new PDFView(this, null);
        pdfView.setBackgroundColor(pageCanvas);
        root.addView(pdfView, new LinearLayout.LayoutParams(-1, 0, 1));

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
            }
        });
        Button zoomIn = button("+", false);
        zoomIn.setOnClickListener(v -> zoomBy(1.25f));

        tools.addView(zoomOut, new LinearLayout.LayoutParams(dp(48), dp(44)));
        LinearLayout.LayoutParams fitParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        fitParams.setMargins(dp(10), 0, dp(10), 0);
        tools.addView(fit, fitParams);
        tools.addView(zoomIn, new LinearLayout.LayoutParams(dp(48), dp(44)));
        LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(-1, dp(60));
        toolsParams.topMargin = dp(10);
        root.addView(tools, toolsParams);

        setContentView(root);
        if (landscape) {
            setReaderControlsVisible(false);
            pdfView.setOnClickListener(v -> toggleReaderControls());
        }

        String path = getIntent().getStringExtra("pdf_path");
        if (path == null || path.trim().isEmpty() || !new File(path).isFile()) {
            loading.setVisibility(View.GONE);
            Toast.makeText(this, "Файл PDF не знайдено", Toast.LENGTH_LONG).show();
            return;
        }
        File file = new File(path);
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
                loading.setVisibility(View.GONE);
                pageCountLabel.setText("СТОРІНКА  /  " + pages);
                updatePageControls(0);
                if (landscape) {
                    pdfView.post(() -> pdfView.resetZoom());
                    setReaderControlsVisible(false);
                }
            })
            .onPageChange((page, pages) -> updatePageControls(page))
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
        // AMOLED reader: true black canvas with restrained dark-grey controls.
        bg = Color.BLACK;
        surface = Color.rgb(10, 10, 10);
        surfaceAlt = Color.rgb(22, 22, 22);
        text = Color.rgb(245, 245, 245);
        muted = Color.rgb(150, 150, 150);
        line = Color.rgb(42, 42, 42);
        accent = Color.rgb(100, 181, 246);
        pageCanvas = Color.rgb(0, 0, 0);
        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(surface);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (!dark && android.os.Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void toggleReaderControls() {
        if (!landscape) return;
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
        if (landscape) {
            if (visible) {
                getWindow().getDecorView().setSystemUiVisibility(dark ? View.SYSTEM_UI_FLAG_LAYOUT_STABLE : View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                );
            }
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && landscape && !controlsVisible) setReaderControlsVisible(false);
    }

    private void updatePageControls(int page) {
        runOnUiThread(() -> {
            pageInput.setText(String.valueOf(page + 1));
            pageCountLabel.setText("СТОРІНКА  /  " + totalPages);
            previous.setEnabled(page > 0);
            next.setEnabled(totalPages > 0 && page < totalPages - 1);
            previous.setAlpha(page > 0 ? 1f : .38f);
            next.setAlpha(totalPages > 0 && page < totalPages - 1 ? 1f : .38f);
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
