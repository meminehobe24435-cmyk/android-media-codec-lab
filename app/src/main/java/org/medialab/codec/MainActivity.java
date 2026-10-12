package org.medialab.codec;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView display;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        display=new TextView(this); display.setTextSize(16); display.setPadding(24,24,24,24);
        ScrollView scroll=new ScrollView(this); scroll.addView(display); setContentView(scroll);
        display.setText("Running synthetic AAC / MP4 regression. No microphone or network access.");
        worker.execute(this::runSuite);
    }
    private void runSuite() {
        JSONObject report=new JSONObject(); JSONArray cases=new JSONArray();
        try {
            Files.deleteIfExists(new File(getFilesDir(),"report.json").toPath());
            report.put("schema",1).put("sdk",Build.VERSION.SDK_INT).put("fingerprint",Build.FINGERPRINT);
            int[][] formats={{48000,1},{44100,2}};
            for (int[] format:formats) for (boolean silent:new boolean[]{false,true}) {
                String name=format[0]+"_"+format[1]+"_"+(silent?"silence":"tone");
                JSONObject row=AudioPipeline.run(new File(getFilesDir(),name+".mp4"),format[0],format[1],silent);
                row.put("name",name); cases.put(row);
            }
            try { WaveNative.parse(new byte[]{1,2,3}); throw new IllegalStateException("bad WAV accepted"); }
            catch (IllegalArgumentException expected) { cases.put(new JSONObject().put("name","truncated_wav").put("status","PASS").put("rejected",true)); }
            File corrupt=new File(getFilesDir(),"corrupt.mp4"); Files.write(corrupt.toPath(),new byte[]{0,0,0,8,'f','t','y','p'});
            boolean rejected=false;
            try { AudioPipeline.decode(corrupt,48000,1); } catch (java.io.IOException|IllegalArgumentException expected) { rejected=true; }
            if (!rejected) throw new IllegalStateException("bad MP4 accepted");
            cases.put(new JSONObject().put("name","truncated_mp4").put("status","PASS").put("rejected",true));
            report.put("status","PASS").put("cases",cases);
        } catch (Exception|LinkageError error) {
            try { report.put("status","FAIL").put("cases",cases).put("error",error.toString()); } catch (org.json.JSONException ignored) { android.util.Log.e("MediaLab","JSON failure",ignored); }
            android.util.Log.e("MediaLab","regression failed",error);
        }
        try {
            String text=report.toString(2);
            File temp=new File(getFilesDir(),"report.tmp"); Files.write(temp.toPath(),text.getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(),new File(getFilesDir(),"report.json").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            runOnUiThread(()-> { if (!isDestroyed()) display.setText(text); });
        } catch (Exception error) { android.util.Log.e("MediaLab","report write failed",error); runOnUiThread(()->display.setText("Report failure: "+error)); }
    }
    @Override public void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
