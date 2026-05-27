package com.handley.myapplication.tcp;

import android.content.Context;
import android.util.Log;
import com.handley.myapplication.common.AssetsFileCopier;
import com.handley.myapplication.common.Utils;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

public class MyClient {

    public interface OnFinishListener {
        void onFinish();
    }

    private static final String TAG = Utils.TAG + "MyClient";
    private final Context context;
    private final File dumpFile;
    private final int port;
    private Thread clientThread;
    private OnFinishListener onFinishListener;

    public MyClient(Context context, File dumpFile, int port) {
        this.context = context;
        this.dumpFile = dumpFile;
        this.port = port;
    }

    public String getDumpFilePath() {
        return dumpFile != null ? dumpFile.getAbsolutePath() : null;
    }

    public void setOnFinishListener(OnFinishListener listener) {
        this.onFinishListener = listener;
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
                if (!this.dumpFile.exists()) {
                    Log.e(TAG, "Dump file not found: " + this.dumpFile.getAbsolutePath());
                    return;
                }

                // 2. 连接到服务器
                socket = new Socket("127.0.0.1", this.port);
                outputStream = socket.getOutputStream();
                Log.i(TAG, "Connected to server");

                // 3. 读取并发送文件
                fis = new FileInputStream(dumpFile);
                bis = new BufferedInputStream(fis);
                byte[] buffer = new byte[1024 * 100];
                int bytesRead;

                long totalBytes = 0;
                while ((bytesRead = bis.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    outputStream.flush();
                    totalBytes += bytesRead;
                    if (totalBytes % (10 * 1024 * 1024) < bytesRead) { // 每1MB打印一次
                        Log.i(TAG, "write: " + (totalBytes / 1024 / 1024) + "MB");
                    }
                    Thread.sleep(10);//一次发 100k 数据，sleep 10ms，每秒最多发 10M 数据。
                }

                Log.i(TAG, "File transfer completed");
            } catch (Exception e) {
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
                // 5. 通知完成
                if (onFinishListener != null) {
                    onFinishListener.onFinish();
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