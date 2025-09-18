package com.handley.myapplication.audio;

import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import com.handley.myapplication.AssetsFileCopier;
import com.handley.myapplication.R;
import com.handley.myapplication.Utils;
import java.io.File;
import java.io.IOException;

// 在Activity中使用
public class OpusActivity extends AppCompatActivity {

    private static final String TAG = Utils.TAG + "OpusActivity";
    private OpusPlayer opusPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 确保有文件读取权限
        File h264File = AssetsFileCopier.copyAssetToExternalFilesDir(this, "test.opus");

        opusPlayer = new OpusPlayer();
        try {
            opusPlayer.prepare(h264File.getAbsolutePath());
            opusPlayer.start();
        } catch (IOException e) {
            Log.e("OpusPlayer", "初始化失败", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (opusPlayer != null) {
            opusPlayer.stop();
        }
    }
}