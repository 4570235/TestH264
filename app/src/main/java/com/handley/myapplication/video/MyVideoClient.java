package com.handley.myapplication.video;

import android.content.Context;
import android.util.Log;
import com.handley.myapplication.common.Utils;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

public class MyVideoClient {

    private static final String TAG = Utils.TAG + "MyVideoClient";
    private final Context context;

    public MyVideoClient(Context context) {
        this.context = context;
    }

    public void sendH264File() {
        new Thread(() -> {
            File file = new File(context.getExternalFilesDir(null), "dump.h264");
            if (!file.exists()) {
                Log.e(TAG, "File not found: " + file.getAbsolutePath());
                return;
            }

            try (Socket socket = new Socket(InetAddress.getByName(MyVideoServer.SERVER_IP), MyVideoServer.SERVER_PORT);
                    DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());
                    FileInputStream fileInputStream = new FileInputStream(file)) {

                Log.i(TAG, "connected to server, start sendH264File");

                // 添加文件传输逻辑
                byte[] buffer = new byte[1024 * 1024];
                int bytesRead;
                long totalBytes = file.length();
                long sentBytes = 0;
                int lastProgress = -1;

                while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    sentBytes += bytesRead;

                    // 进度日志（每10%更新一次）
                    int progress = (int) ((sentBytes * 100) / totalBytes);
                    if (progress != lastProgress && progress % 10 == 0) {
                        Log.d(TAG, "Transfer progress: " + progress + "%");
                        lastProgress = progress;
                    }
                }
                outputStream.flush();  // 确保所有数据发送完毕
                Log.i(TAG, "File transmission completed. Total bytes sent: " + sentBytes);
            } catch (IOException e) {
                Log.e(TAG, "Client error: " + e.getMessage());
            }
        }).start();
    }
}