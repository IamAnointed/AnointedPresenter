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
import android.util.AtomicFile;
import java.io.FileInputStream;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.widget.ImageView;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import org.json.JSONObject;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.NetworkInterface;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Enumeration;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URLDecoder;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> pending;
    private static final int FILE_REQUEST=21;
    private static final int SECTION_VIDEO_REQUEST=22;
    private static final int EXPORT_LIBRARY_REQUEST=23;
    private static final int SLIDE_DECK_REQUEST=24;
    private String pendingExportJson=null;
    private String sectionVideoKey="";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private LyricsPresentation output;
    private String lastLyrics="{}";
    private String videoPath="";
    private ServerSocket remoteServer;
    private volatile boolean remoteEnabled=false;
    private String remotePin="";
    private DisplayManager displayManager;
    private final DisplayManager.DisplayListener listener=new DisplayManager.DisplayListener(){
        public void onDisplayAdded(int id){}
        public void onDisplayChanged(int id){}
        public void onDisplayRemoved(int id){ui.post(()->{if(output!=null && output.getDisplay().getDisplayId()==id){closePresentation();showMessage("External display disconnected.");}});}
    };
    @Override public void onCreate(Bundle state){super.onCreate(state);displayManager=(DisplayManager)getSystemService(Context.DISPLAY_SERVICE);displayManager.registerDisplayListener(listener,ui);web=makeWebView();File savedVideo=new File(getFilesDir(),"projector_background.mp4");if(savedVideo.exists())videoPath=savedVideo.getAbsolutePath();web.addJavascriptInterface(new Bridge(),"JA1Android");setContentView(web);web.loadUrl("file:///android_asset/index.html");}
    private WebView makeWebView(){
        WebView w=new WebView(this);WebSettings s=w.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(true);s.setJavaScriptCanOpenWindowsAutomatically(true);s.setSupportMultipleWindows(false);
        w.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest req){
                Uri uri=req.getUrl();if("appassets.androidplatform.net".equals(uri.getHost())&&uri.getPath().startsWith("/slide/")){
                    int idx=currentSlide+(uri.getPath().endsWith("/next")?1:0);
                    return new WebResourceResponse("image/jpeg",null,new java.io.ByteArrayInputStream(renderSlideJpeg(idx)));
                }
                return super.shouldInterceptRequest(view,req);
            }
        });
        w.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> callback,FileChooserParams params){
                if(pending!=null)pending.onReceiveValue(null);pending=callback;
                try{Intent intent=params.createIntent();intent.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,FILE_REQUEST);return true;}
                catch(Exception e){pending=null;showMessage("Could not open file picker: "+e.getMessage());return false;}
            }
        });return w;
    }
    private void bibleResult(String kind,JSONObject data){
        ui.post(()->web.evaluateJavascript("bibleApiCallback("+JSONObject.quote(kind)+","+data.toString()+")",null));
    }
    private JSONObject apiError(String message){JSONObject o=new JSONObject();try{o.put("ok",false);o.put("error",message);}catch(Exception ignored){}return o;}
    private void requestBible(String kind,String path){
        new Thread(()->{
            String key=getSharedPreferences("anointed_private",MODE_PRIVATE).getString("bible_key","");
            if(key.isEmpty()){bibleResult(kind,apiError("Save your API key first."));return;}
            if(!path.startsWith("/v1/bibles")||path.contains("://")||path.contains("..")){bibleResult(kind,apiError("Invalid API path"));return;}
            HttpURLConnection conn=null;
            try{
                URL url=new URL("https://api.scripture.api.bible"+path);
                conn=(HttpURLConnection)url.openConnection();conn.setConnectTimeout(12000);conn.setReadTimeout(20000);
                conn.setRequestProperty("api-key",key);conn.setRequestProperty("Accept","application/json");
                int status=conn.getResponseCode();
                try(InputStream in=status<400?conn.getInputStream():conn.getErrorStream()){
                    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
                    while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>3000000)throw new Exception("Response too large");}
                    JSONObject obj=new JSONObject(out.toString("UTF-8"));obj.put("ok",status>=200&&status<300);
                    if(status>=400)obj.put("error","HTTP "+status+": "+obj.optString("message",obj.optString("error","Access denied")));
                    bibleResult(kind,obj);
                }
            }catch(Exception e){bibleResult(kind,apiError(e.getMessage()));}
            finally{if(conn!=null)conn.disconnect();}
        },"BibleApi").start();
    }
    private final Object stateLock=new Object();
    private File stateFile(){return new File(getFilesDir(),"anointed_library_state.json");}
    private String readSavedState(){
        synchronized(stateLock){
            try{
                AtomicFile f=new AtomicFile(stateFile());
                try(FileInputStream in=f.openRead()){
                    ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int n;
                    while((n=in.read(buffer))!=-1){b.write(buffer,0,n);if(b.size()>25000000)throw new Exception("Library too large");}
                    return b.toString("UTF-8");
                }
            }catch(Exception e){return "";}
        }
    }
    private boolean writeSavedState(String value){
        synchronized(stateLock){
            AtomicFile f=new AtomicFile(stateFile());FileOutputStream out=null;
            try{
                JSONObject o=new JSONObject(value);
                if(o.getJSONArray("library").length()<1)return false;
                out=f.startWrite();out.write(value.getBytes("UTF-8"));f.finishWrite(out);return true;
            }catch(Exception e){
                if(out!=null)f.failWrite(out);
                android.util.Log.e("AnointedSave","Could not save library",e);return false;
            }
        }
    }
    private void startLibraryExport(String json){
        ui.post(()->{
            try{
                JSONObject o=new JSONObject(json);
                if(!o.has("library")||o.getJSONArray("library").length()==0)throw new Exception("No songs in library");
                pendingExportJson=json;
                Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE,"anointed-presenter-library.json");
                startActivityForResult(i,EXPORT_LIBRARY_REQUEST);
            }catch(Exception e){pendingExportJson=null;showMessage("Export failed: "+e.getMessage());}
        });
    }
    private void finishLibraryExport(Uri uri){
        final String json=pendingExportJson;pendingExportJson=null;
        if(json==null||uri==null)return;
        new Thread(()->{
            try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){
                if(out==null)throw new Exception("Unable to open selected file");
                out.write(json.getBytes("UTF-8"));out.flush();
                ui.post(()->showMessage("Library exported successfully. Find it in the folder you selected."));
            }catch(Exception e){ui.post(()->showMessage("Export failed: "+e.getMessage()));}
        },"LibraryExport").start();
    }
    private File slideFile(){return new File(getFilesDir(),"presenter_slides.pdf");}
    private int pdfPages(){
        File f=slideFile();if(!f.exists())return 0;
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){return renderer.getPageCount();}
        catch(Exception e){return 0;}
    }
    private Bitmap slideBitmap(int index){
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(slideFile(),ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){
            if(index<0||index>=renderer.getPageCount())return null;
            try(PdfRenderer.Page page=renderer.openPage(index)){
                int w=1280,h=Math.max(1,Math.round(w*(float)page.getHeight()/page.getWidth()));
                if(h>2400){h=2400;w=Math.max(1,Math.round(h*(float)page.getWidth()/page.getHeight()));}
                Bitmap bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);bmp.eraseColor(Color.WHITE);
                page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);return bmp;
            }
        }catch(Exception e){android.util.Log.e("SlidePDF","Rendering failed",e);return null;}
    }
    private void importPdfSlides(){ui.post(()->{
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/pdf");i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,SLIDE_DECK_REQUEST);
    });}
    private void copyPdfSlides(Uri uri){new Thread(()->{
        File tmp=new File(getFilesDir(),"presenter_slides.tmp"),target=slideFile();
        try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(tmp)){
            if(in==null)throw new Exception("Cannot open PDF");byte[] b=new byte[65536];int n;long total=0;
            while((n=in.read(b))!=-1){total+=n;if(total>150000000)throw new Exception("PDF exceeds 150 MB");out.write(b,0,n);}
            out.flush();
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(tmp,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer r=new PdfRenderer(fd)){if(r.getPageCount()<1)throw new Exception("No slides found");}
            if(target.exists()&&!target.delete())throw new Exception("Cannot replace old slides");
            if(!tmp.renameTo(target))throw new Exception("Cannot save slides");
            int count=pdfPages();ui.post(()->{web.evaluateJavascript("slideDeckReady("+count+")",null);showMessage("Imported "+count+" slides. Open Projector Output to present.");});
        }catch(Exception e){tmp.delete();ui.post(()->showMessage("Slide import failed: "+e.getMessage()));}
    },"SlideImport").start();}
    private byte[] renderSlideJpeg(int index){Bitmap b=slideBitmap(index);if(b==null)return new byte[0];try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
        b.compress(Bitmap.CompressFormat.JPEG,88,out);return out.toByteArray();
    }catch(Exception e){return new byte[0];}finally{b.recycle();}}
    private volatile int currentSlide=0;
    private volatile boolean showingSlides=false;
    private class Bridge {
        @JavascriptInterface public void importSlideDeck(){importPdfSlides();}
        @JavascriptInterface public int getSlideCount(){return pdfPages();}
        @JavascriptInterface public void removeSlideDeck(){ui.post(()->{slideFile().delete();showingSlides=false;currentSlide=0;if(output!=null)output.showSlide(null,false);});}
        @JavascriptInterface public void showSongs(){ui.post(()->{showingSlides=false;if(output!=null)output.showSlide(null,false);});}
        @JavascriptInterface public void showSlide(int index,boolean black){ui.post(()->{currentSlide=index;showingSlides=true;if(output!=null)output.showSlide(black?null:slideBitmap(index),black);});}
        @JavascriptInterface public void exportPresenterLibrary(String json){startLibraryExport(json);}
        @JavascriptInterface public String loadPresenterState(){return readSavedState();}
        @JavascriptInterface public boolean savePresenterState(String json){return writeSavedState(json);}

        @JavascriptInterface public void saveBibleApiKey(String key){
            getSharedPreferences("anointed_private",MODE_PRIVATE).edit().putString("bible_key",key.trim()).apply();
            bibleResult("save",new JSONObject(java.util.Collections.singletonMap("ok",true)));
        }
        @JavascriptInterface public void removeBibleApiKey(){
            getSharedPreferences("anointed_private",MODE_PRIVATE).edit().remove("bible_key").apply();
            bibleResult("remove",new JSONObject(java.util.Collections.singletonMap("ok",true)));
        }
        @JavascriptInterface public void bibleApiRequest(String kind,String path){requestBible(kind,path);}

        @JavascriptInterface public void setSectionVideo(String key){ui.post(()->{
            sectionVideoKey=key.replaceAll("[^0-9:]","");
            try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("video/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,SECTION_VIDEO_REQUEST);}catch(Exception e){showMessage("Video picker unavailable: "+e.getMessage());}
        });}
        @JavascriptInterface public void openOutput(){ui.post(()->openPresentation());}
        @JavascriptInterface public void closeOutput(){ui.post(()->closePresentation());}
        @JavascriptInterface public String startRemote(){return MainActivity.this.startRemoteServer();}
        @JavascriptInterface public void stopRemote(){MainActivity.this.stopRemoteServer();}
        @JavascriptInterface public void updateLyrics(String json){ui.post(()->{lastLyrics=json;if(output!=null){output.render(json);try{JSONObject o=new JSONObject(json);String key=o.optString("sectionVideoKey","default");File f=key.equals("default")?new File(getFilesDir(),"projector_background.mp4"):new File(getFilesDir(),"section_"+key.replace(':','_')+".mp4");output.setVideo(o.optBoolean("videoActive",false)&&f.exists()?f.getAbsolutePath():"");}catch(Exception ignored){}}});}
    }
    private synchronized String startRemoteServer(){
        if(remoteEnabled)return "Remote: http://"+localIp()+":8765/ — PIN: "+remotePin;
        try{
            remoteServer=new ServerSocket(8765);
            remotePin=String.format(java.util.Locale.US,"%06d",new SecureRandom().nextInt(1000000));
            remoteEnabled=true;
            new Thread(()->{
                while(remoteEnabled){try{Socket socket=remoteServer.accept();new Thread(()->handleRemote(socket)).start();}catch(Exception e){if(remoteEnabled)android.util.Log.w("JA1Remote","Remote connection",e);}}
            },"JA1-Remote").start();
            return "Remote: http://"+localIp()+":8765/ — PIN: "+remotePin;
        }catch(Exception e){return "Could not start remote: "+e.getMessage();}
    }
    private synchronized void stopRemoteServer(){remoteEnabled=false;try{if(remoteServer!=null)remoteServer.close();}catch(Exception ignored){}remoteServer=null;}
    private String localIp(){
        try{Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces.hasMoreElements()){NetworkInterface ni=interfaces.nextElement();if(!ni.isUp()||ni.isLoopback())continue;
                Enumeration<InetAddress> addresses=ni.getInetAddresses();while(addresses.hasMoreElements()){
                    InetAddress a=addresses.nextElement();if(a instanceof Inet4Address&&!a.isLoopbackAddress()&&a.isSiteLocalAddress())return a.getHostAddress();
                }}
        }catch(Exception ignored){}return "TABLET-IP";
    }
    private String parameter(String data,String key){
        for(String part:data.split("&")){String[] kv=part.split("=",2);if(kv.length==2&&kv[0].equals(key))try{return URLDecoder.decode(kv[1],"UTF-8");}catch(Exception ignored){}}
        return "";
    }
    private void handleRemote(Socket socket){
        try(Socket client=socket){client.setSoTimeout(5000);
            BufferedReader reader=new BufferedReader(new InputStreamReader(client.getInputStream(),"UTF-8"));
            String request=reader.readLine();if(request==null)return;
            int length=0;String header;
            while((header=reader.readLine())!=null&&!header.isEmpty()){
                if(header.toLowerCase(java.util.Locale.US).startsWith("content-length:"))length=Math.min(2048,Integer.parseInt(header.substring(15).trim()));
            }
            String response;String status="200 OK";
            if(request.startsWith("GET / ")||request.startsWith("GET /?")){
                response="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><title>JA1 Remote</title><style>body{font:18px system-ui;background:#111827;color:white;text-align:center;padding:24px}button,input{font-size:20px;padding:16px;border-radius:10px;margin:7px}button{background:#3159b9;color:white;border:0;min-width:130px}input{width:180px}#status{color:#aabbd5}</style></head><body><h2>JA1 Church Presenter Remote</h2><p>Enter pairing PIN shown on the tablet</p><input id='pin' type='password' inputmode='numeric' maxlength='6' placeholder='6-digit PIN'><div><button data-action='prev' onclick='send(this.dataset.action)'>◀ Previous</button><button data-action='next' onclick='send(this.dataset.action)'>Next ▶</button></div><div><button data-action='clear' onclick='send(this.dataset.action)'>Clear Lyrics</button><button data-action='black' onclick='send(this.dataset.action)'>Blackout</button><button data-action='live' onclick='send(this.dataset.action)'>Go Live</button></div><div><button data-action='setprev' onclick='send(this.dataset.action)'>Previous Song</button><button data-action='setnext' onclick='send(this.dataset.action)'>Next Song</button></div><p id='status'></p><script>async function send(a){let p=document.getElementById('pin').value;try{let r=await fetch('/cmd',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'pin='+encodeURIComponent(p)+'&action='+encodeURIComponent(a)});document.getElementById('status').textContent=await r.text()}catch(e){document.getElementById('status').textContent='Connection failed'}}</script></body></html>";
            }else if(request.startsWith("POST /cmd ")){
                char[] body=new char[length];int total=0;while(total<length){int n=reader.read(body,total,length-total);if(n<0)break;total+=n;}
                String form=new String(body,0,total);String pin=parameter(form,"pin"),action=parameter(form,"action");
                if(!pin.equals(remotePin)){status="403 Forbidden";response="Incorrect PIN";}
                else if(!java.util.Arrays.asList("prev","next","clear","black","live","setprev","setnext").contains(action)){status="400 Bad Request";response="Unknown command";}
                else{ui.post(()->web.evaluateJavascript("remoteCommand('"+action+"')",null));response="Sent: "+action;}
            }else{status="404 Not Found";response="Not found";}
            byte[] bytes=response.getBytes("UTF-8");
            OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 "+status+"\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(bytes);out.flush();
        }catch(Exception e){android.util.Log.w("JA1Remote","Request failed",e);}
    }
    private void openPresentation(){
        if(output!=null&&output.isShowing()){if(showingSlides)output.showSlide(slideBitmap(currentSlide),false);else output.showSlide(null,false);output.render(lastLyrics);try{JSONObject o=new JSONObject(lastLyrics);String key=o.optString("sectionVideoKey","default");File f=key.equals("default")?new File(getFilesDir(),"projector_background.mp4"):new File(getFilesDir(),"section_"+key.replace(':','_')+".mp4");output.setVideo(o.optBoolean("videoActive",false)&&f.exists()?f.getAbsolutePath():"");}catch(Exception ignored){}return;}
        Display[] displays=displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        Display chosen=null;
        for(Display d:displays){if(d.getDisplayId()!=web.getDisplay().getDisplayId()){chosen=d;break;}}
        if(chosen==null){showMessage("No independent projector display detected. Huawei HDMI may be in mirror-only mode. Presenter view will stay on tablet.");return;}
        try{output=new LyricsPresentation(this,chosen);output.show();output.render(lastLyrics);output.setVideo(videoPath);if(showingSlides)output.showSlide(slideBitmap(currentSlide),false);showMessage("Lyrics output opened on projector.");}
        catch(Exception e){output=null;showMessage("Cannot open projector: "+e.getMessage());}
    }
    private void closePresentation(){if(output!=null){output.dismiss();output=null;}}
    private void showMessage(String s){android.widget.Toast.makeText(this,s,android.widget.Toast.LENGTH_LONG).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==EXPORT_LIBRARY_REQUEST){
            if(result==RESULT_OK&&data!=null&&data.getData()!=null)finishLibraryExport(data.getData());
            else pendingExportJson=null;
            return;
        }
        if(request==SLIDE_DECK_REQUEST){if(result==RESULT_OK&&data!=null&&data.getData()!=null)copyPdfSlides(data.getData());return;}
        if(request==SECTION_VIDEO_REQUEST){if(result==RESULT_OK&&data!=null&&data.getData()!=null)copySectionVideo(data.getData(),sectionVideoKey);return;}
        if(request==FILE_REQUEST&&pending!=null){
            Uri[] chosen=WebChromeClient.FileChooserParams.parseResult(result,data);
            pending.onReceiveValue(chosen);pending=null;
            if(chosen!=null&&chosen.length>0){
                Uri uri=chosen[0];
                String mime=getContentResolver().getType(uri);
                if(mime!=null&&mime.startsWith("video/"))copyVideoForProjector(uri);
            }
        }}
    private void copySectionVideo(Uri uri,String key){
        new Thread(()->{
            File target=new File(getFilesDir(),"section_"+key.replace(':','_')+".mp4");
            try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(target)){
                byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
                ui.post(()->{showMessage("Section video saved.");web.evaluateJavascript("sectionVideoSaved("+org.json.JSONObject.quote(key)+")",null);});
            }catch(Exception e){ui.post(()->showMessage("Section video failed: "+e.getMessage()));}
        }).start();
    }
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
    @Override protected void onDestroy(){stopRemoteServer();closePresentation();displayManager.unregisterDisplayListener(listener);web.destroy();super.onDestroy();}
    @Override public void onBackPressed(){if(web.canGoBack())web.goBack();else super.onBackPressed();}
    private static class LyricsPresentation extends Presentation {
        private FrameLayout root;private ImageView slideView;private TextView words;private VideoView video;private android.view.View shade;private String currentVideo="";private boolean isBlack=false;
        LyricsPresentation(Context context,Display display){super(context,display);}
        @Override protected void onCreate(Bundle b){super.onCreate(b);root=new FrameLayout(getContext());root.setBackgroundColor(Color.BLACK);
            video=new VideoView(getContext());
            root.addView(video,new FrameLayout.LayoutParams(-1,-1));
            slideView=new ImageView(getContext());slideView.setBackgroundColor(Color.BLACK);slideView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            root.addView(slideView,new FrameLayout.LayoutParams(-1,-1));slideView.setVisibility(android.view.View.GONE);
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
            video.setVisibility(isBlack?android.view.View.GONE:android.view.View.VISIBLE);shade.setVisibility(isBlack?android.view.View.GONE:android.view.View.VISIBLE);
            video.setVideoPath(path);
            video.start();
        }
        void showSlide(Bitmap bitmap,boolean black){
            if(slideView==null)return;
            if(bitmap==null&&!black){slideView.setImageDrawable(null);slideView.setVisibility(android.view.View.GONE);return;}
            slideView.setVisibility(android.view.View.VISIBLE);slideView.bringToFront();
            if(black){slideView.setImageDrawable(null);slideView.setBackgroundColor(Color.BLACK);}
            else{slideView.setBackgroundColor(Color.BLACK);slideView.setImageBitmap(bitmap);}
        }
        void render(String json){if(root==null)return;try{JSONObject d=new JSONObject(json);
            isBlack=d.optBoolean("blackout",false);
            String newText=d.optString("text","");
            int fade=Math.max(0,Math.min(1500,d.optInt("fadeMs",400)));
            if(d.optBoolean("clearLyrics",false)||isBlack)newText="";
            final String targetText=newText;
            if(!targetText.equals(words.getText().toString())&&fade>0){
                words.animate().cancel();words.animate().alpha(0f).setDuration(fade/2).withEndAction(()->{words.setText(targetText);words.animate().alpha(1f).setDuration(fade/2).start();}).start();
            }else{words.animate().cancel();words.setAlpha(1f);words.setText(targetText);}
            video.setVisibility(isBlack||currentVideo.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);
            int dim=Math.max(0,Math.min(80,d.optInt("videoDim",35)));
            shade.setBackgroundColor(Color.argb((int)(255*dim/100.0),0,0,0));
            shade.setVisibility(isBlack||currentVideo.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);words.setTextColor(Color.parseColor(d.optString("color","#ffffff")));root.setBackgroundColor(Color.parseColor(d.optString("bg","#000000")));words.setTextSize(Math.min(110,Math.max(18,d.optInt("size",52))));String requestedFont=d.optString("font","Arial");
            String androidFont="sans-serif";
            if("Georgia".equals(requestedFont)||"Times New Roman".equals(requestedFont))androidFont="serif";
            else if("Impact".equals(requestedFont))androidFont="sans-serif-condensed";
            else if("Tahoma".equals(requestedFont)||"Verdana".equals(requestedFont))androidFont="sans-serif";
            else if("Trebuchet MS".equals(requestedFont))androidFont="sans-serif-medium";
            String weight=d.optString("weight","700");
            int weightValue="900".equals(weight)?900:("400".equals(weight)?400:700);
            android.graphics.Typeface family=android.graphics.Typeface.create(androidFont,android.graphics.Typeface.NORMAL);
            if(android.os.Build.VERSION.SDK_INT>=28)words.setTypeface(android.graphics.Typeface.create(family,weightValue,false));
            else words.setTypeface(android.graphics.Typeface.create(androidFont,weightValue>=700?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL));String a=d.optString("align","center");words.setGravity(Gravity.CENTER_VERTICAL|("left".equals(a)?Gravity.LEFT:"right".equals(a)?Gravity.RIGHT:Gravity.CENTER_HORIZONTAL));String image=isBlack?"":d.optString("image","");if(image.startsWith("data:image/")&&image.contains(",")){try{byte[] bytes=android.util.Base64.decode(image.substring(image.indexOf(',')+1),android.util.Base64.DEFAULT);android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);root.setBackground(new android.graphics.drawable.BitmapDrawable(getContext().getResources(),bitmap));}catch(Exception ignored){}}else root.setBackgroundColor(Color.parseColor(isBlack?"#000000":d.optString("bg","#000000")));}catch(Exception ignored){}}
    }
}
