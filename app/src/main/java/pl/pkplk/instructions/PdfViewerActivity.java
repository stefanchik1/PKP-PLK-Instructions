package pl.pkplk.instructions;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.File;

public class PdfViewerActivity extends Activity {
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private ImageView pageImage;
    private TextView pageLabel;
    private int pageIndex=0;
    private LinearLayout root;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(28,38,50));
        LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(12,8,12,8);
        Button prev=new Button(this); prev.setText("‹"); prev.setOnClickListener(v->showPage(pageIndex-1));
        Button next=new Button(this); next.setText("›"); next.setOnClickListener(v->showPage(pageIndex+1));
        pageLabel=new TextView(this); pageLabel.setTextColor(Color.WHITE); pageLabel.setGravity(Gravity.CENTER); pageLabel.setTextSize(14);
        bar.addView(prev,new LinearLayout.LayoutParams(56,48)); bar.addView(pageLabel,new LinearLayout.LayoutParams(0,48,1)); bar.addView(next,new LinearLayout.LayoutParams(56,48));
        root.addView(bar);
        ScrollView scroll=new ScrollView(this); pageImage=new ImageView(this); pageImage.setAdjustViewBounds(true); pageImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        scroll.addView(pageImage); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
        try {
            File f=new File(getIntent().getStringExtra("pdf_path"));
            descriptor=ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);
            renderer=new PdfRenderer(descriptor); showPage(0);
        } catch(Exception e) { pageLabel.setText("Nie można otworzyć PDF"); }
    }
    private void showPage(int index) {
        if(renderer==null||index<0||index>=renderer.getPageCount()) return;
        pageIndex=index; PdfRenderer.Page page=renderer.openPage(index);
        int width=Math.max(500,getResources().getDisplayMetrics().widthPixels-24);
        int height=Math.max(1,(int)(width*(page.getHeight()/(float)page.getWidth())));
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE); page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        page.close(); pageImage.setImageBitmap(bitmap); pageLabel.setText((index+1)+" / "+renderer.getPageCount());
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        try { if(renderer!=null)renderer.close(); if(descriptor!=null)descriptor.close(); } catch(Exception ignored) {}
    }
}