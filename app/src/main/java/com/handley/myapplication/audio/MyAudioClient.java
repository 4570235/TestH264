package com.handley.myapplication.audio;

import android.content.Context;
import android.util.Log;
import com.handley.myapplication.Utils;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

// 音频客户端实现
public class MyAudioClient {

    private static final String TAG = Utils.TAG + "MyAudioClient";
    private final Context context;

    public MyAudioClient(Context context) {
        this.context = context;
    }

    public void sendOpusFile() {
        new Thread(() -> {
            File file = new File(context.getExternalFilesDir(null), "test.opus");
            if (!file.exists()) {
                Log.e(TAG, "Audio file not found: " + file.getAbsolutePath());
                return;
            }

            try (Socket socket = new Socket(InetAddress.getByName(MyAudioServer.SERVER_IP), MyAudioServer.SERVER_PORT);
                    DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());
                    FileInputStream fileInputStream = new FileInputStream(file)) {

                Log.i(TAG, "Audio client connected to server. Starting file transfer...");

                byte[] buffer = new byte[5000];
                int bytesRead;
                long totalBytes = file.length();
                long sentBytes = 0;
                int lastProgress = -1;

                while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    sentBytes += bytesRead;

                    int progress = (int) ((sentBytes * 100) / totalBytes);
                    if (progress != lastProgress && progress % 10 == 0) {
                        Log.d(TAG, "Audio transfer progress: " + progress + "%");
                        lastProgress = progress;
                    }
                }
                outputStream.flush();
                Log.i(TAG, "Audio file transmission completed. Total bytes sent: " + sentBytes);
            } catch (IOException e) {
                Log.e(TAG, "Audio client error: " + e.getMessage());
            }
        }).start();
    }
}