package com.handley.myapplication.audio;


import android.util.Log;
import androidx.annotation.NonNull;
import com.handley.myapplication.MediaMessageHeader;
import com.handley.myapplication.Utils;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

// 音频服务器实现
public class MyAudioServer {

    static final String SERVER_IP = "127.0.0.1";
    static final int SERVER_PORT = 23333;
    private static final String TAG = Utils.TAG + "MyAudioServer";
    private static final int BUFFER_SIZE = 5000;
    private final OnOpusDataListener listener;
    private ServerSocket serverSocket;
    private boolean isRunning = true;

    public MyAudioServer(@NonNull OnOpusDataListener listener) {
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

            Log.i(TAG, "startServer() listening on port: " + SERVER_PORT);

            while (isRunning) {
                Socket clientSocket = serverSocket.accept();
                Log.i(TAG,
                        "startServer() New audio client connected: " + clientSocket.getInetAddress().getHostAddress());

                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException e) {
            Log.e(TAG, "MyAudioServer error: " + e.getMessage());
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
                Log.v(TAG, "handleClient() bytesRead=" + bytesRead + " processed=" + processed + " bufferOffset="
                        + bufferOffset);

                if (processed > 0) {
                    System.arraycopy(buffer, processed, buffer, 0, bufferOffset - processed);
                    bufferOffset -= processed;
                } else if (processed < 0) {
                    Log.e(TAG, "Invalid audio message format");
                    break;
                }
            }

            Log.i(TAG, "handleClient() Audio client disconnected: " + clientSocket.getInetAddress().getHostAddress());
            clientSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "Audio client handling error: " + e.getMessage());
        }
    }

    private int processBuffer(byte[] buffer, int bufferLength) {
        if (bufferLength < MediaMessageHeader.SIZE) {
            return 0;
        }

        MediaMessageHeader header = new MediaMessageHeader();//MediaMessageHeader.parse(buffer);
        header.magic = MediaMessageHeader.MAGIC;
        header.type = MediaMessageHeader.OPUS;
        header.dataLen = bufferLength - MediaMessageHeader.SIZE;
        if (header.magic != MediaMessageHeader.MAGIC) {
            Log.e(TAG, "Invalid audio magic number: 0x" + Integer.toHexString(header.magic));
            return -1;
        }

        int totalMessageSize = MediaMessageHeader.SIZE + header.dataLen;
        if (bufferLength < totalMessageSize) {
            return 0;
        }

        byte[] payload = Arrays.copyOfRange(buffer, MediaMessageHeader.SIZE, totalMessageSize);
        handleAudioMessage(header, payload);

        return totalMessageSize;
    }

    private void handleAudioMessage(MediaMessageHeader header, byte[] payload) {
        if (header.type == MediaMessageHeader.OPUS) {
            Log.i(TAG,
                    String.format("handleAudioMessage() OPUS audio: ts=%d, len=%d", header.timestamp, payload.length));
            processOpusData(payload, header.timestamp);
        }
    }

    private void processOpusData(byte[] data, long timestamp) {
        listener.onOpusDataReceived(data, timestamp);
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "MyAudioServer shutdown error: " + e.getMessage());
        }
    }

    // 音频数据监听接口
    public interface OnOpusDataListener {

        void onOpusDataReceived(byte[] data, long timestamp);
    }
}
