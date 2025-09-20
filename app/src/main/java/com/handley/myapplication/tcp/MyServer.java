package com.handley.myapplication.tcp;


import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.handley.myapplication.common.MyFrameCallback;
import com.handley.myapplication.common.Utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class MyServer {
    private static final String TAG = Utils.TAG + "MyServer";
    private final int port;
    private final String outputFileName;
    private final Context context;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean isRunning = false;
    private OutputStream fileOutputStream;

    // 移除MyFrameCallback参数
    public MyServer(MyFrameCallback callback, int port, String outputFileName, Context context) {
        this.port = port;
        this.outputFileName = outputFileName;
        this.context = context.getApplicationContext();
    }

    public void start() {
        if (isRunning) {
            Log.w(TAG, "Server already running");
            return;
        }

        // 初始化文件输出流
        if (!TextUtils.isEmpty(outputFileName)) {
            File dumpFile = new File(this.context.getExternalFilesDir(null), outputFileName);
            try {
                fileOutputStream = new BufferedOutputStream(new FileOutputStream(dumpFile.getAbsolutePath()));
                Log.i(TAG, "Output file opened: " + dumpFile.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "Error opening output file: " + e.getMessage());
                fileOutputStream = null;
            }
        }

        isRunning = true;
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(this.port);
                Log.i(TAG, "Server started on port " + this.port);

                while (isRunning) {
                    try (Socket clientSocket = serverSocket.accept();
                         InputStream inputStream = clientSocket.getInputStream();
                         BufferedInputStream bis = new BufferedInputStream(inputStream)) {

                        Log.i(TAG, "Client connected: " + clientSocket.getInetAddress());
                        processClientData(bis);
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "Client connection error: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Server error: " + e.getMessage());
            } finally {
                closeServerSocket();
                closeFileOutputStream();
            }
        });

        serverThread.start();
    }

    private void processClientData(BufferedInputStream bis) throws IOException {
        byte[] buffer = new byte[8192]; // 8KB缓冲区
        int bytesRead;

        while (isRunning && (bytesRead = bis.read(buffer)) != -1) {
            if (bytesRead > 0 && fileOutputStream != null) {
                try {
                    fileOutputStream.write(buffer, 0, bytesRead);
                } catch (IOException e) {
                    Log.e(TAG, "File write error: " + e.getMessage());
                    closeFileOutputStream();
                    break;
                }
            }
        }
        Log.i(TAG, "Client disconnected");
    }

    public void stop() {
        isRunning = false;

        closeServerSocket();
        closeFileOutputStream();

        if (serverThread != null && serverThread.isAlive()) {
            serverThread.interrupt();
            try {
                serverThread.join(500);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while stopping server thread");
            }
        }
    }

    private void closeServerSocket() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                Log.i(TAG, "closeServerSocket()");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing server socket: " + e.getMessage());
        }
    }

    private void closeFileOutputStream() {
        if (fileOutputStream != null) {
            try {
                fileOutputStream.flush();
                fileOutputStream.close();
                Log.i(TAG, "Output file closed");
            } catch (IOException e) {
                Log.e(TAG, "Error closing file stream: " + e.getMessage());
            } finally {
                fileOutputStream = null;
            }
        }
    }
}