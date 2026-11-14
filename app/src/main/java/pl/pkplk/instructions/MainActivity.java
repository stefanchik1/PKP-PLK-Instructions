package pl.pkplk.instructions;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final String ROOT = "https://www.plk-sa.pl/klienci-i-kontrahenci/akty-prawne-i-przepisy/instrukcje-pkp-polskich-linii-kolejowych-sa";
    private static final String[] SLUGS = {
        "ruch-i-przewozy-kolejowe", "automatyka-i-telekomunikacja", "linie-kolejowe",
        "ochrona-srodowiska", "geodezja-kolejowa", "geologia-inzynierska",
        "elektroenergetyka-kolejowa", "bezpieczenstwo-i-higiena-pracy",
        "sprawy-organizacyjno-administracyjne", "infrastruktura-pasazerska",
        "pojazdy-kolejowe", "logistyka", ""
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setLoadsImagesAutomatically(true);
        web.addJavascriptInterface(new AppBridge(), "PLKApp");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private class AppBridge {
        @JavascriptInterface public void refreshCatalog() {
            new Thread(() -> {
                JSONArray result = new JSONArray();
                int ok = 0;
                for (int i = 0; i < SLUGS.length; i++) {
                    String page = SLUGS[i].isEmpty() ? ROOT : ROOT + "/" + SLUGS[i];
                    try {
                        String html = download(page);
                        JSONArray links = extractDocuments(html, page);
                        JSONObject category = new JSONObject();
                        category.put("index", i);
                        category.put("url", page);
                        category.put("documents", links);
                        result.put(category);
                        ok++;
                    } catch (Exception ignored) { }
                }
                final int success = ok;
                final JSONArray data = result;
                main.post(() -> {
                    if (web != null) web.evaluateJavascript("window.onCatalogRefresh && window.onCatalogRefresh(" + data.toString() + "," + success + ")", null);
                });
            }).start();
        }
    }

    private String download(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(12000);
        c.setReadTimeout(16000);
        c.setRequestProperty("User-Agent", "PKP-PLK-Instructions-Android/1.1 (catalogue metadata checker)");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
            r.close(); return b.toString();
        } finally { c.disconnect(); }
    }

    private JSONArray extractDocuments(String html, String base) throws Exception {
        JSONArray out = new JSONArray();
        Pattern p = Pattern.compile("(?is)<a\\b[^>]*href\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"][^>]*>(.*?)</a>");
        Matcher m = p.matcher(html);
        while (m.find()) {
            String href = m.group(1).replace("&amp;", "&");
            String label = m.group(2).replaceAll("(?is)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            String lower = (href + " " + label).toLowerCase();
            if (!(lower.contains(".pdf") || label.toLowerCase().contains("pobierz pdf") || label.toLowerCase().contains("pobierz zip"))) continue;
            if (href.startsWith("/")) href = "https://www.plk-sa.pl" + href;
            else if (!href.startsWith("http")) href = base + "/" + href;
            if (label.isEmpty()) label = "Dokument PDF / ZIP";
            JSONObject item = new JSONObject(); item.put("title", label); item.put("url", href); out.put(item);
        }
        return out;
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
