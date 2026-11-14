package pl.pkplk.instructions;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
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
    private static final int PICK_PDF_REQUEST = 4102;
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
        @JavascriptInterface public void addOwnInstruction() {
            runOnUiThread(() -> {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("application/pdf");
                startActivityForResult(pick, PICK_PDF_REQUEST);
            });
        }
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
                    URL requested = checkedUrl(address);
                    String actualAddress = requested.toString();
                    // Static catalogue cards may point to a category page, not directly to a PDF.
                    if (!actualAddress.toLowerCase().matches(".*\\.pdf([?#].*)?$")) {
                        String pageHtml = downloadText(actualAddress);
                        JSONArray candidates = extractDocuments(pageHtml, actualAddress);
                        String wantedCode = title == null ? "" : title.split("\\s+", 2)[0].trim();
                        String selected = null;
                        for (int i = 0; i < candidates.length(); i++) {
                            JSONObject candidate = candidates.optJSONObject(i);
                            if (candidate == null) continue;
                            String candidateTitle = candidate.optString("title", "");
                            if (!wantedCode.isEmpty() && candidateTitle.toLowerCase().contains(wantedCode.toLowerCase())) {
                                selected = candidate.optString("url"); break;
                            }
                        }
                        if (selected == null && candidates.length() > 0) selected = candidates.optJSONObject(0).optString("url");
                        if (selected == null || selected.isEmpty()) throw new IOException("Nie znaleziono bezpośredniego pliku PDF na stronie PKP PLK");
                        actualAddress = selected;
                    }
                    URL url = checkedUrl(actualAddress);
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

        @JavascriptInterface public void renameDownloadedDocument(String filename, String newTitle) {
            if (newTitle == null || newTitle.trim().isEmpty()) return;
            JSONArray a=readSaved();
            for(int i=0;i<a.length();i++){
                JSONObject x=a.optJSONObject(i);
                if(x!=null && filename.equals(x.optString("file"))) {
                    try { x.put("title",newTitle.trim()); } catch(Exception ignored) {}
                }
            }
            getPreferences(MODE_PRIVATE).edit().putString("saved_pdfs",a.toString()).apply();
            main.post(() -> js("window.onOfflineList && window.onOfflineList(" + a + ")"));
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


    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode!=PICK_PDF_REQUEST || resultCode!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData();
        new Thread(() -> {
            try {
                File dir=new File(getFilesDir(),"plk_documents");
                if(!dir.exists()&&!dir.mkdirs()) throw new IOException("Nie można utworzyć katalogu");
                String displayName="Własna instrukcja.pdf";
                android.database.Cursor cursor=getContentResolver().query(uri,null,null,null,null);
                if(cursor!=null) {
                    try {
                        int col=cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if(cursor.moveToFirst()&&col>=0) displayName=cursor.getString(col);
                    } finally { cursor.close(); }
                }
                String key="custom_"+System.currentTimeMillis();
                File target=new File(dir,key+".pdf");
                try(InputStream in=getContentResolver().openInputStream(uri); FileOutputStream out=new FileOutputStream(target)) {
                    if(in==null) throw new IOException("Nie można otworzyć pliku");
                    byte[] buf=new byte[16384]; int n; boolean header=false; long total=0;
                    while((n=in.read(buf))!=-1) {
                        if(!header) {
                            if(n<5||buf[0]!='%'||buf[1]!='P'||buf[2]!='D'||buf[3]!='F'||buf[4]!='-')
                                throw new IOException("Wybrany plik nie jest poprawnym PDF");
                            header=true;
                        }
                        total+=n;
                        if(total>100L*1024*1024) throw new IOException("Limit pliku: 100 MB");
                        out.write(buf,0,n);
                    }
                }
                JSONObject item=new JSONObject();
                item.put("title",displayName.replaceAll("(?i)\\.pdf$",""));
                item.put("file",target.getName()); item.put("url","");
                item.put("size",target.length()); item.put("savedAt",System.currentTimeMillis());
                item.put("custom",true);
                JSONArray all=readSaved(); all.put(item);
                getPreferences(MODE_PRIVATE).edit().putString("saved_pdfs",all.toString()).apply();
                main.post(()->js("window.onOfflineList && window.onOfflineList("+all+")"));
            } catch(Exception e) {
                String msg=e.getMessage()==null?"Błąd importu":e.getMessage();
                main.post(()->js("window.onPdfError && window.onPdfError('Własna instrukcja',"+JSONObject.quote(msg)+")"));
            }
        }).start();
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
        java.util.LinkedHashMap<String, String> unique = new java.util.LinkedHashMap<>();
        while(m.find()) {
            String href=m.group(1).replace("&amp;","&");
            String label=m.group(2).replaceAll("(?is)<[^>]+>"," ").replaceAll("\\s+"," ").trim();
            String lower=(href+" "+label).toLowerCase();
            if(!lower.contains(".pdf")) continue;
            if(href.startsWith("/")) href="https://www.plk-sa.pl"+href;
            else if(!href.startsWith("http")) href=base+"/"+href;
            try { checkedUrl(href); } catch(Exception ignored) { continue; }
            String cleanTitle = label.isEmpty() ? "Dokument PDF" : label;
            String old = unique.get(href);
            if (old == null || cleanTitle.length() > old.length()) unique.put(href, cleanTitle);
        }
        for (java.util.Map.Entry<String, String> entry : unique.entrySet()) {
            JSONObject item=new JSONObject(); item.put("title",entry.getValue()); item.put("url",entry.getKey()); out.put(item);
        }
        return out;
    }
    @Override public void onBackPressed() {
        if(web!=null&&web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}