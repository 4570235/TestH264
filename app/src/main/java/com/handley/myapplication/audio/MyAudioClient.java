package com.handley.myapplication.audio;

import android.content.Context;
import android.util.Log;
import com.handley.myapplication.AssetsFileCopier;
import com.handley.myapplication.Utils;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

public class MyAudioClient {

    private static final String TAG = Utils.TAG + "MyAudioClient";
    private final Context context;
    private Thread clientThread;

    public MyAudioClient(Context context) {
        this.context = context;
    }

    public void start() {
        if (clientThread != null && clientThread.isAlive()) {
            Log.w(TAG, "Client already running");
            return;
        }

        clientThread = new Thread(() -> {
            Socket socket = null;
            OutputStream outputStream = null;
            FileInputStream fis = null;
            BufferedInputStream bis = null;

            try {
                // 1. 获取dump文件路径
                File dumpFile = AssetsFileCopier.copyAssetToExternalFilesDir(this.context, "fake-dump.opus");
                if (!dumpFile.exists()) {
                    Log.e(TAG, "Dump file not found: " + dumpFile.getAbsolutePath());
                    return;
                }

                // 2. 连接到服务器
                socket = new Socket(MyAudioServer.SERVER_IP, MyAudioServer.SERVER_PORT);
                outputStream = socket.getOutputStream();
                Log.i(TAG, "Connected to server");

                // 3. 读取并发送文件
                fis = new FileInputStream(dumpFile);
                bis = new BufferedInputStream(fis);
                byte[] buffer = new byte[4096];
                int bytesRead;

                while ((bytesRead = bis.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    outputStream.flush();
                }

                Log.i(TAG, "File transfer completed");
            } catch (IOException e) {
                Log.e(TAG, "Client error: " + e.getMessage());
            } finally {
                // 4. 关闭资源
                try {
                    if (bis != null) {
                        bis.close();
                    }
                    if (fis != null) {
                        fis.close();
                    }
                    if (outputStream != null) {
                        outputStream.close();
                    }
                    if (socket != null) {
                        socket.close();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error closing resources: " + e.getMessage());
                }
            }
        });

        clientThread.start();
    }

    public void stop() {
        if (clientThread != null && clientThread.isAlive()) {
            clientThread.interrupt();
            try {
                clientThread.join(500);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while stopping client thread");
            }
        }
    }
}