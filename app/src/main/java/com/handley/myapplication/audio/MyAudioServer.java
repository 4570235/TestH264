package com.handley.myapplication.audio;


import android.util.Log;
import com.handley.myapplication.MediaMessageHeader;
import com.handley.myapplication.Utils;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class MyAudioServer {

    static final String SERVER_IP = "127.0.0.1";
    static final int SERVER_PORT = 23333;
    private static final String TAG = Utils.TAG + "MyAudioServer";
    private final FrameCallback frameCallback;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean isRunning = false;

    public MyAudioServer(FrameCallback callback) {
        this.frameCallback = callback;
    }

    public void start() {
        if (isRunning) {
            Log.w(TAG, "Server already running");
            return;
        }

        isRunning = true;
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(SERVER_PORT);
                Log.i(TAG, "Server started on port " + SERVER_PORT);

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
            }
        });

        serverThread.start();
    }

    private void processClientData(BufferedInputStream bis) throws IOException {
        byte[] headerBuffer = new byte[MediaMessageHeader.SIZE];
        int bytesRead;

        while (isRunning) {
            // 1. 读取帧头
            bytesRead = bis.read(headerBuffer, 0, MediaMessageHeader.SIZE);
            if (bytesRead != MediaMessageHeader.SIZE) {
                if (bytesRead == -1) {
                    Log.i(TAG, "End of stream reached");
                } else {
                    Log.w(TAG, "Incomplete header: " + bytesRead + " bytes");
                }
                break;
            }

            // 2. 解析帧头
            MediaMessageHeader header = MediaMessageHeader.parse(headerBuffer);
            if (header.magic != MediaMessageHeader.MAGIC) {
                Log.e(TAG, "Invalid magic number: 0x" + Integer.toHexString(header.magic));
                break;
            }

            // 3. 读取帧数据
            byte[] frameData = new byte[header.dataLen];
            bytesRead = bis.read(frameData, 0, header.dataLen);
            if (bytesRead != header.dataLen) {
                Log.e(TAG, "Incomplete frame data: expected " + header.dataLen + ", got " + bytesRead);
                break;
            }

            // 4. 回调帧数据
            if (frameCallback != null) {
                header.timestamp /= 1000;//转换为毫秒
                frameCallback.onFrameReceived(header, frameData);
            }
        }
    }

    private void closeServerSocket() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing server socket: " + e.getMessage());
        }
    }

    public void stop() {
        isRunning = false;
        closeServerSocket();

        if (serverThread != null && serverThread.isAlive()) {
            serverThread.interrupt();
            try {
                serverThread.join(500);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while stopping server thread");
            }
        }
    }

    public interface FrameCallback {

        void onFrameReceived(MediaMessageHeader header, byte[] frameData);
    }
}