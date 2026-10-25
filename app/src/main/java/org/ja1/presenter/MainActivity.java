package org.ja1.presenter;

import android.app.Activity;
import android.app.Presentation;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.net.Uri;
import android.webkit.*;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.VideoView;
import android.media.MediaPlayer;
import android.graphics.drawable.ColorDrawable;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import android.graphics.Color;
import android.view.Gravity;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> pending;
    private static final int FILE_REQUEST=21;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private LyricsPresentation output;
    private String lastLyrics="{}";
    private String videoPath="";
    private DisplayManager displayManager;
    private final DisplayManager.DisplayListener listener=new DisplayManager.DisplayListener(){
        public void onDisplayAdded(int id){}
        public void onDisplayChanged(int id){}
        public void onDisplayRemoved(int id){ui.post(()->{if(output!=null && output.getDisplay().getDisplayId()==id){closePresentation();showMessage("External display disconnected.");}});}
    };
    @Override public void onCreate(Bundle state){super.onCreate(state);displayManager=(DisplayManager)getSystemService(Context.DISPLAY_SERVICE);displayManager.registerDisplayListener(listener,ui);web=makeWebView();web.addJavascriptInterface(new Bridge(),"JA1Android");setContentView(web);web.loadUrl("file:///android_asset/index.html");}
    private WebView makeWebView(){
        WebView w=new WebView(this);WebSettings s=w.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(true);s.setJavaScriptCanOpenWindowsAutomatically(true);s.setSupportMultipleWindows(false);
        w.setWebViewClient(new WebViewClient());
        w.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> callback,FileChooserParams params){
                if(pending!=null)pending.onReceiveValue(null);pending=callback;
                try{Intent intent=params.createIntent();intent.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,FILE_REQUEST);return true;}
                catch(Exception e){pending=null;showMessage("Could not open file picker: "+e.getMessage());return false;}
            }
        });return w;
    }
    private class Bridge {
        @JavascriptInterface public void openOutput(){ui.post(()->openPresentation());}
        @JavascriptInterface public void closeOutput(){ui.post(()->closePresentation());}
        @JavascriptInterface public void updateLyrics(String json){ui.post(()->{lastLyrics=json;if(output!=null)output.render(json);});}
    }
    private void openPresentation(){
        if(output!=null&&output.isShowing()){output.render(lastLyrics);output.setVideo(videoPath);return;}
        Display[] displays=displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        Display chosen=null;
        for(Display d:displays){if(d.getDisplayId()!=web.getDisplay().getDisplayId()){chosen=d;break;}}
        if(chosen==null){showMessage("No independent projector display detected. Huawei HDMI may be in mirror-only mode. Presenter view will stay on tablet.");return;}
        try{output=new LyricsPresentation(this,chosen);output.show();output.render(lastLyrics);output.setVideo(videoPath);showMessage("Lyrics output opened on projector.");}
        catch(Exception e){output=null;showMessage("Cannot open projector: "+e.getMessage());}
    }
    private void closePresentation(){if(output!=null){output.dismiss();output=null;}}
    private void showMessage(String s){android.widget.Toast.makeText(this,s,android.widget.Toast.LENGTH_LONG).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==FILE_REQUEST&&pending!=null){
            Uri[] chosen=WebChromeClient.FileChooserParams.parseResult(result,data);
            pending.onReceiveValue(chosen);pending=null;
            if(chosen!=null&&chosen.length>0){
                Uri uri=chosen[0];
                String mime=getContentResolver().getType(uri);
                if(mime!=null&&mime.startsWith("video/"))copyVideoForProjector(uri);
            }
        }}
    private void copyVideoForProjector(Uri uri){
        new Thread(()->{
            File temp=new File(getFilesDir(),"projector_background.tmp");
            File target=new File(getFilesDir(),"projector_background.mp4");
            try(InputStream in=getContentResolver().openInputStream(uri);
                OutputStream out=new FileOutputStream(temp)){
                if(in==null)throw new Exception("Unable to read video");
                byte[] buf=new byte[65536];int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
                out.flush();
                if(target.exists())target.delete();
                if(!temp.renameTo(target))throw new Exception("Unable to save video");
                ui.post(()->{videoPath=target.getAbsolutePath();if(output!=null)output.setVideo(videoPath);showMessage("Projector video background ready.");});
            }catch(Exception e){ui.post(()->showMessage("Video import failed: "+e.getMessage()));}
        }).start();
    }
    @Override protected void onDestroy(){closePresentation();displayManager.unregisterDisplayListener(listener);web.destroy();super.onDestroy();}
    @Override public void onBackPressed(){if(web.canGoBack())web.goBack();else super.onBackPressed();}
    private static class LyricsPresentation extends Presentation {
        private FrameLayout root;private TextView words;private VideoView video;private android.view.View shade;private String currentVideo="";
        LyricsPresentation(Context context,Display display){super(context,display);}
        @Override protected void onCreate(Bundle b){super.onCreate(b);root=new FrameLayout(getContext());root.setBackgroundColor(Color.BLACK);
            video=new VideoView(getContext());
            root.addView(video,new FrameLayout.LayoutParams(-1,-1));
            video.setVisibility(android.view.View.GONE);
            video.setOnPreparedListener(mp->{mp.setLooping(true);mp.setVolume(0f,0f);mp.start();});
            shade=new android.view.View(getContext());
            root.addView(shade,new FrameLayout.LayoutParams(-1,-1));
            words=new TextView(getContext());words.setGravity(Gravity.CENTER);words.setTextColor(Color.WHITE);words.setTextSize(52);words.setPadding(30,30,30,30);root.addView(words,new FrameLayout.LayoutParams(-1,-1));setContentView(root);getWindow().getDecorView().setSystemUiVisibility(5894|1024|512);}
        void setVideo(String path){
            if(video==null)return;
            if(path==null||path.isEmpty()){video.stopPlayback();video.setVisibility(android.view.View.GONE);shade.setVisibility(android.view.View.GONE);currentVideo="";return;}
            if(path.equals(currentVideo))return;
            currentVideo=path;
            video.setVisibility(android.view.View.VISIBLE);shade.setVisibility(android.view.View.VISIBLE);
            video.setVideoPath(path);
            video.start();
        }
        void render(String json){if(root==null)return;try{JSONObject d=new JSONObject(json);words.setText(d.optString("text",""));
            int dim=Math.max(0,Math.min(80,d.optInt("videoDim",35)));
            shade.setBackgroundColor(Color.argb((int)(255*dim/100.0),0,0,0));
            shade.setVisibility(currentVideo.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);words.setTextColor(Color.parseColor(d.optString("color","#ffffff")));root.setBackgroundColor(Color.parseColor(d.optString("bg","#000000")));words.setTextSize(Math.min(110,Math.max(18,d.optInt("size",52))));words.setTypeface(android.graphics.Typeface.create(d.optString("font","sans-serif"),"700".equals(d.optString("weight"))||"900".equals(d.optString("weight"))?1:0));String a=d.optString("align","center");words.setGravity(Gravity.CENTER_VERTICAL|("left".equals(a)?Gravity.LEFT:"right".equals(a)?Gravity.RIGHT:Gravity.CENTER_HORIZONTAL));String image=d.optString("image","");if(image.startsWith("data:image/")&&image.contains(",")){try{byte[] bytes=android.util.Base64.decode(image.substring(image.indexOf(',')+1),android.util.Base64.DEFAULT);android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);root.setBackground(new android.graphics.drawable.BitmapDrawable(getContext().getResources(),bitmap));}catch(Exception ignored){}}else root.setBackgroundColor(Color.parseColor(d.optString("bg","#000000")));}catch(Exception ignored){}}
    }
}
