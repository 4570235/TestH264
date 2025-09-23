package com.handley.myapplication.tcp;


import android.util.Log;
import androidx.annotation.NonNull;
import com.handley.myapplication.common.MediaMessageHeader;
import com.handley.myapplication.common.MyFrame;
import com.handley.myapplication.common.MyFrameCallback;
import com.handley.myapplication.common.Utils;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class MyServer {

    private static final String TAG = Utils.TAG + "MyServer";
    @NonNull
    private final MyFrameCallback myFrameCallback;
    private final int port;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean isRunning = false;

    public MyServer(@NonNull MyFrameCallback callback, int port) {
        this.myFrameCallback = callback;
        this.port = port;
    }

    public void start() {
        if (isRunning) {
            Log.w(TAG, "Server already running");
            return;
        }

        isRunning = true;
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(this.port);
                Log.i(TAG, "start() Server started on port " + this.port);

                while (isRunning) {
                    try (Socket clientSocket = serverSocket.accept();
                            InputStream inputStream = clientSocket.getInputStream();
                            BufferedInputStream bis = new BufferedInputStream(inputStream)) {

                        Log.i(TAG, "start() Client connected: " + clientSocket.getInetAddress());
                        processClientData(bis);
                        Log.i(TAG, "start() finish Client data: " + clientSocket.getInetAddress());
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "start() Client connection error: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "start() Server error: " + e.getMessage());
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
            Log.d(TAG, "Received frame: type=" + header.type + ", length=" + header.dataLen + ", timestamp="
                    + header.timestamp);
            myFrameCallback.onFrameReceived(new MyFrame(header, frameData));
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
        Log.i(TAG, "stop() finish");
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
}