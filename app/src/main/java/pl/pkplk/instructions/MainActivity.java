package pl.pkplk.instructions;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

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
                JSONArray result = new JSONArray(); int ok = 0;
                for (int i = 0; i < SLUGS.length; i++) {
                    String page = SLUGS[i].isEmpty() ? ROOT : ROOT + "/" + SLUGS[i];
                    try {
                        String html = downloadText(page);
                        JSONObject category = new JSONObject();
                        category.put("index", i); category.put("url", page);
                        category.put("documents", extractDocuments(html, page));
                        result.put(category); ok++;
                    } catch (Exception ignored) { }
                }
                final int success = ok; final JSONArray data = result;
                main.post(() -> { if (web != null) web.evaluateJavascript(
                    "window.onCatalogRefresh && window.onCatalogRefresh(" + data + "," + success + ")", null); });
            }).start();
        }

        @JavascriptInterface public void downloadPdf(String address, String title) {
            new Thread(() -> {
                try {
                    URL url = checkedUrl(address);
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setInstanceFollowRedirects(true); c.setConnectTimeout(20000); c.setReadTimeout(30000);
                    c.setRequestProperty("User-Agent", "PKP-PLK-Instructions-Android/2.0");
                    int code = c.getResponseCode();
                    URL finalUrl = checkedUrl(c.getURL().toString());
                    if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
                    File dir = new File(getFilesDir(), "plk_documents");
                    if (!dir.exists() && !dir.mkdirs()) throw new IOException("Nie można utworzyć katalogu");
                    String key = sha256(finalUrl.toString()).substring(0, 16);
                    File target = new File(dir, key + ".pdf"), temp = new File(dir, key + ".part");
                    int total = c.getContentLength(); int done = 0;
                    try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
                        byte[] buf = new byte[16384]; int n; boolean checked = false;
                        while ((n = in.read(buf)) != -1) {
                            if (!checked) {
                                if (n < 5 || buf[0] != '%' || buf[1] != 'P' || buf[2] != 'D' || buf[3] != 'F' || buf[4] != '-') throw new IOException("Serwer nie zwrócił pliku PDF");
                                checked = true;
                            }
                            done += n;
                            if (done > 100 * 1024 * 1024) throw new IOException("Plik przekracza limit 100 MB");
                            out.write(buf, 0, n);
                            final int progress = total > 0 ? Math.min(99, done * 100 / total) : -1;
                            final String safeTitle = title;
                            main.post(() -> js("window.onPdfProgress && window.onPdfProgress(" + JSONObject.quote(safeTitle) + "," + progress + ")"));
                        }
                    } finally { c.disconnect(); }
                    if (!temp.renameTo(target)) throw new IOException("Nie udało się zapisać pliku");
                    JSONObject item = new JSONObject();
                    item.put("title", title); item.put("file", target.getName()); item.put("url", finalUrl.toString());
                    item.put("size", target.length()); item.put("savedAt", System.currentTimeMillis());
                    JSONArray all = readSaved();
                    for (int i = all.length()-1; i >= 0; i--) if (all.optJSONObject(i) != null && target.getName().equals(all.optJSONObject(i).optString("file"))) all.remove(i);
                    all.put(item);
                    getPreferences(MODE_PRIVATE).edit().putString("saved_pdfs", all.toString()).apply();
                    main.post(() -> { js("window.onPdfDownloaded && window.onPdfDownloaded(" + item + ")"); });
                } catch (Exception e) {
                    String message = e.getMessage() == null ? "Błąd pobierania" : e.getMessage();
                    main.post(() -> js("window.onPdfError && window.onPdfError(" + JSONObject.quote(title) + "," + JSONObject.quote(message) + ")"));
                }
            }).start();
        }

        @JavascriptInterface public void listDownloadedDocuments() {
            JSONArray a = readSaved();
            main.post(() -> js("window.onOfflineList && window.onOfflineList(" + a + ")"));
        }

        @JavascriptInterface public void openDownloadedDocument(String filename) {
            File f = new File(new File(getFilesDir(), "plk_documents"), new File(filename).getName());
            if (!f.isFile()) return;
            main.post(() -> {
                Intent i = new Intent(MainActivity.this, PdfViewerActivity.class);
                i.putExtra("pdf_path", f.getAbsolutePath()); startActivity(i);
            });
        }

        @JavascriptInterface public void deleteDownloadedDocument(String filename) {
            File f = new File(new File(getFilesDir(), "plk_documents"), new File(filename).getName());
            if (f.isFile()) f.delete();
            JSONArray a = readSaved();
            for (int i=a.length()-1;i>=0;i--) if (filename.equals(a.optJSONObject(i).optString("file"))) a.remove(i);
            getPreferences(MODE_PRIVATE).edit().putString("saved_pdfs",a.toString()).apply();
            main.post(() -> js("window.onOfflineList && window.onOfflineList(" + a + ")"));
        }
    }

    private void js(String code) { if (web != null) web.evaluateJavascript(code, null); }
    private JSONArray readSaved() {
        try { return new JSONArray(getPreferences(MODE_PRIVATE).getString("saved_pdfs", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    private URL checkedUrl(String value) throws Exception {
        URL u = new URL(value);
        String h = u.getHost().toLowerCase();
        if (!"https".equalsIgnoreCase(u.getProtocol()) || !(h.equals("plk-sa.pl") || h.endsWith(".plk-sa.pl")))
            throw new IOException("Dozwolone są wyłącznie oficjalne adresy plk-sa.pl");
        return u;
    }
    private String sha256(String s) throws Exception {
        byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(); for (byte x:b) out.append(String.format("%02x",x)); return out.toString();
    }
    private String downloadText(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) checkedUrl(address).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(16000);
        c.setRequestProperty("User-Agent","PKP-PLK-Instructions-Android/2.0");
        try {
            int code=c.getResponseCode(); checkedUrl(c.getURL().toString());
            if(code<200||code>=300) throw new IOException("HTTP "+code);
            BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder(); String line; while((line=r.readLine())!=null)b.append(line).append('\n');
            r.close(); return b.toString();
        } finally { c.disconnect(); }
    }
    private JSONArray extractDocuments(String html, String base) throws Exception {
        JSONArray out=new JSONArray();
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?is)<a\\b[^>]*href\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"][^>]*>(.*?)</a>").matcher(html);
        while(m.find()) {
            String href=m.group(1).replace("&amp;","&");
            String label=m.group(2).replaceAll("(?is)<[^>]+>"," ").replaceAll("\\s+"," ").trim();
            String lower=(href+" "+label).toLowerCase();
            if(!(lower.contains(".pdf")||label.toLowerCase().contains("pobierz pdf"))) continue;
            if(href.startsWith("/")) href="https://www.plk-sa.pl"+href;
            else if(!href.startsWith("http")) href=base+"/"+href;
            try { checkedUrl(href); } catch(Exception ignored) { continue; }
            JSONObject item=new JSONObject(); item.put("title",label.isEmpty()?"Dokument PDF":label); item.put("url",href); out.put(item);
        }
        return out;
    }
    @Override public void onBackPressed() {
        if(web!=null&&web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}