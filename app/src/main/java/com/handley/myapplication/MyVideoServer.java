package com.handley.myapplication;

import android.util.Log;
import androidx.annotation.NonNull;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

public class MyVideoServer {

    static final String SERVER_IP = "127.0.0.1";
    static final int SERVER_PORT = 24443;//23334;
    private static final String TAG = Utils.TAG + "MyVideoServer";
    private static final int BUFFER_SIZE = 1024 * 1024; // 1MB
    private final OnH264DataListener listener;
    private ServerSocket serverSocket;
    private boolean isRunning = true;

    public MyVideoServer(@NonNull OnH264DataListener listener) {
        this.listener = listener;
    }

    public void start() {
        new Thread(this::startServer).start();
    }

    private void startServer() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName(SERVER_IP), SERVER_PORT));

            Log.i(TAG, "TCP server started, listening on port: " + SERVER_PORT);

            while (isRunning) {
                Socket clientSocket = serverSocket.accept();
                Log.i(TAG, "New client connected: " + clientSocket.getInetAddress().getHostAddress());

                // 为每个客户端创建新线程处理
                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException e) {
            Log.e(TAG, "MyVideoServer error: " + e.getMessage());
        }
    }

    private void handleClient(Socket clientSocket) {
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bufferOffset = 0;
            int bytesRead;

            while ((bytesRead = clientSocket.getInputStream().read(buffer, bufferOffset, BUFFER_SIZE - bufferOffset))
                    != -1) {
                bufferOffset += bytesRead;
                int processed = processBuffer(buffer, bufferOffset);

                if (processed > 0) {
                    // 移除已处理数据
                    System.arraycopy(buffer, processed, buffer, 0, bufferOffset - processed);
                    bufferOffset -= processed;
                } else if (processed < 0) {
                    Log.e(TAG, "Invalid message format");
                    break;
                }
                if (!isRunning) {
                    break;
                }
            }

            Log.i(TAG, "Client disconnected: " + clientSocket.getInetAddress().getHostAddress());
            clientSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "Client handling error: " + e.getMessage());
        }
    }

    private int processBuffer(byte[] buffer, int bufferLength) {
        if (bufferLength < MediaMessageHeader.SIZE) {
            return 0; // 数据不足
        }

        MediaMessageHeader header = MediaMessageHeader.parse(buffer);
        if (header.magic != MediaMessageHeader.MAGIC) {
            Log.e(TAG, "Invalid magic number: 0x" + Integer.toHexString(header.magic));
            return -1; // 协议错误
        }

        int totalMessageSize = MediaMessageHeader.SIZE + header.dataLen;
        if (bufferLength < totalMessageSize) {
            return 0; // 数据不足
        }

        // 提取有效载荷
        byte[] payload = Arrays.copyOfRange(buffer, MediaMessageHeader.SIZE, totalMessageSize);
        handleMediaMessage(header, payload);

        return totalMessageSize; // 返回已处理字节数
    }

    private void handleMediaMessage(MediaMessageHeader header, byte[] payload) {
        String typeName;
        switch (header.type) {
            case MediaMessageHeader.H264:
                typeName = "H264";
                break;
            case MediaMessageHeader.H265:
                typeName = "H265";
                break;
            case MediaMessageHeader.VP8:
                typeName = "VP8";
                break;
            default:
                typeName = "Unknown";
        }

        Log.d(TAG, String.format("Received type=%s, ts=%d, rotation=%d, len=%d", typeName, header.timestamp,
                header.rotation, payload.length));

        // 这里添加解码/渲染逻辑
        if (header.type == MediaMessageHeader.H264) {
            processH264Data(payload, header.timestamp, header.rotation);
        }
    }

    private void processH264Data(byte[] data, long timestamp, int rotation) {
        listener.onDataReceived(data, timestamp, rotation);
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "MyServer shutdown error: " + e.getMessage());
        }
    }


    public interface OnH264DataListener {

        void onDataReceived(byte[] data, long timestamp, int rotation);
    }
}
