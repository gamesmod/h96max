package org.tvbox.iptv;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.VideoView;
import android.media.MediaPlayer;

import java.util.HashMap;
import java.util.Map;

/** Temporary diagnostic path comparing Android MediaPlayer with VLC. */
public final class NativePlaybackActivity extends Activity {
    private VideoView video;
    private TextView status;
    private String epgId;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        epgId = MainActivity.safeLogValue(getIntent().getStringExtra("epg_id"));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(20);
        status.setGravity(Gravity.CENTER);
        status.setText("Подключаю канал…");
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        Uri uri = getIntent().getData();
        if (uri == null) { fail("missing_uri"); return; }
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "VLC/3.0.25 LibVLC/3.0.25");
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer player) {
                MainActivity.iptvLogStatic(NativePlaybackActivity.this, "native_player_prepared", "epg_id=" + epgId + " width=" + player.getVideoWidth() + " height=" + player.getVideoHeight());
                status.setVisibility(TextView.GONE);
                video.start();
            }
        });
        video.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override public boolean onInfo(MediaPlayer player, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    MainActivity.iptvLogStatic(NativePlaybackActivity.this, "native_video_rendering_start", "epg_id=" + epgId);
                    status.setVisibility(TextView.GONE);
                }
                return false;
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer player, int what, int extra) {
                fail("what=" + what + " extra=" + extra);
                return true;
            }
        });
        MainActivity.iptvLogStatic(this, "native_player_open", "epg_id=" + epgId);
        video.setVideoURI(uri, headers);
    }

    private void fail(String reason) {
        status.setText("Не удалось запустить канал");
        MainActivity.iptvLogStatic(this, "native_player_error", "epg_id=" + epgId + " reason=" + reason);
    }
    @Override public void onBackPressed() { video.stopPlayback(); super.onBackPressed(); }
    @Override protected void onStop() { if (video != null && video.isPlaying()) video.stopPlayback(); super.onStop(); }
}
