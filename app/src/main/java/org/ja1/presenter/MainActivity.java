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
        w.setWebViewClient(new WebViewClient());
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
    private class Bridge {
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
        if(output!=null&&output.isShowing()){output.render(lastLyrics);try{JSONObject o=new JSONObject(lastLyrics);String key=o.optString("sectionVideoKey","default");File f=key.equals("default")?new File(getFilesDir(),"projector_background.mp4"):new File(getFilesDir(),"section_"+key.replace(':','_')+".mp4");output.setVideo(o.optBoolean("videoActive",false)&&f.exists()?f.getAbsolutePath():"");}catch(Exception ignored){}return;}
        Display[] displays=displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        Display chosen=null;
        for(Display d:displays){if(d.getDisplayId()!=web.getDisplay().getDisplayId()){chosen=d;break;}}
        if(chosen==null){showMessage("No independent projector display detected. Huawei HDMI may be in mirror-only mode. Presenter view will stay on tablet.");return;}
        try{output=new LyricsPresentation(this,chosen);output.show();output.render(lastLyrics);output.setVideo(videoPath);showMessage("Lyrics output opened on projector.");}
        catch(Exception e){output=null;showMessage("Cannot open projector: "+e.getMessage());}
    }
    private void closePresentation(){if(output!=null){output.dismiss();output=null;}}
    private void showMessage(String s){android.widget.Toast.makeText(this,s,android.widget.Toast.LENGTH_LONG).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==SECTION_VIDEO_REQUEST){if(result==RESULT_OK&&data!=null&&data.getData()!=null)copySectionVideo(data.getData(),sectionVideoKey);return;}
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
        private FrameLayout root;private TextView words;private VideoView video;private android.view.View shade;private String currentVideo="";private boolean isBlack=false;
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
            video.setVisibility(isBlack?android.view.View.GONE:android.view.View.VISIBLE);shade.setVisibility(isBlack?android.view.View.GONE:android.view.View.VISIBLE);
            video.setVideoPath(path);
            video.start();
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
            shade.setVisibility(isBlack||currentVideo.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);words.setTextColor(Color.parseColor(d.optString("color","#ffffff")));root.setBackgroundColor(Color.parseColor(d.optString("bg","#000000")));words.setTextSize(Math.min(110,Math.max(18,d.optInt("size",52))));words.setTypeface(android.graphics.Typeface.create(d.optString("font","sans-serif"),"700".equals(d.optString("weight"))||"900".equals(d.optString("weight"))?1:0));String a=d.optString("align","center");words.setGravity(Gravity.CENTER_VERTICAL|("left".equals(a)?Gravity.LEFT:"right".equals(a)?Gravity.RIGHT:Gravity.CENTER_HORIZONTAL));String image=isBlack?"":d.optString("image","");if(image.startsWith("data:image/")&&image.contains(",")){try{byte[] bytes=android.util.Base64.decode(image.substring(image.indexOf(',')+1),android.util.Base64.DEFAULT);android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);root.setBackground(new android.graphics.drawable.BitmapDrawable(getContext().getResources(),bitmap));}catch(Exception ignored){}}else root.setBackgroundColor(Color.parseColor(isBlack?"#000000":d.optString("bg","#000000")));}catch(Exception ignored){}}
    }
}
