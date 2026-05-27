package com.handley.myapplication.tcp;


import android.util.Log;
import androidx.annotation.NonNull;
import com.handley.myapplication.common.MediaMessageHeader;
import com.handley.myapplication.common.MyFrame;
import com.handley.myapplication.common.MyFrameCallback;
import com.handley.myapplication.common.Utils;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MyServer {

    private static final String TAG = Utils.TAG + "MyServer";
    @NonNull
    private final MyFrameCallback myFrameCallback;
    private final int port;
    private final File dumpBaseDir;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean isRunning = false;
    private File dumpDir;
    private File dumpFile;
    private FileOutputStream dumpFos;

    public MyServer(@NonNull MyFrameCallback callback, int port, File dumpBaseDir) {
        this.myFrameCallback = callback;
        this.port = port;
        this.dumpBaseDir = dumpBaseDir;
    }

    public File getDumpFile() {
        return dumpFile;
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }

    private void closeDumpFile() {
        try {
            if (dumpFos != null) {
                dumpFos.close();
                dumpFos = null;
                Log.i(TAG, "closeDumpFile()");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing dump file: " + e.getMessage());
        }
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

                // Create dump directory and file (only if dumpBaseDir is not null)
                if (dumpBaseDir != null) {
                    dumpDir = new File(dumpBaseDir, "dump");
                    if (!dumpDir.exists() && !dumpDir.mkdirs()) {
                        Log.e(TAG, "start() Failed to create dump directory: " + dumpDir.getAbsolutePath());
                        return;
                    }

                    // Create dump file with timestamp: dump_port{port}_yyyyMMdd_HHmmss.h264
                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                    String fileName = "dump_port" + port + "_" + timeStamp + ".h264";
                    dumpFile = new File(dumpDir, fileName);
                    try {
                        dumpFos = new FileOutputStream(dumpFile);
                        Log.i(TAG, "start() Dump file created: " + dumpFile.getAbsolutePath());
                    } catch (IOException e) {
                        Log.e(TAG, "start() Failed to create dump file: " + e.getMessage());
                        return;
                    }
                } else {
                    Log.i(TAG, "start() Dump disabled (dumpBaseDir is null)");
                }

                while (isRunning) {
                    try (Socket clientSocket = serverSocket.accept();
                            InputStream inputStream = clientSocket.getInputStream()) {

                        Log.i(TAG, "start() Client connected: " + clientSocket.getInetAddress());

                        // 用 TeeInputStream 包装 inputStream：读到的数据同时写入 dumpFos
                        InputStream teeInputStream = dumpFos != null
                                ? new com.handley.myapplication.common.TeeInputStream(inputStream, dumpFos)
                                : inputStream;
                        BufferedInputStream bis = new BufferedInputStream(teeInputStream);

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
                closeDumpFile();
            }
        });

        serverThread.start();
    }

    private void processClientData(BufferedInputStream bis) throws IOException {
        byte[] headerBuffer = new byte[MediaMessageHeader.SIZE];
        int bytesRead;

        while (isRunning) {
            // 1. 读取帧头
            int totalHeaderBytesRead = 0;
            while (totalHeaderBytesRead < MediaMessageHeader.SIZE && isRunning) {
                bytesRead = bis.read(headerBuffer, totalHeaderBytesRead,
                        MediaMessageHeader.SIZE - totalHeaderBytesRead);
                if (bytesRead == -1) {
                    Log.i(TAG, "End of stream reached during header read");
                    break;
                }
                totalHeaderBytesRead += bytesRead;
            }
            if (totalHeaderBytesRead != MediaMessageHeader.SIZE) {
                Log.w(TAG, "Incomplete header: expected " + MediaMessageHeader.SIZE + ", got " + totalHeaderBytesRead);
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
            int totalBytesRead = 0;
            while (totalBytesRead < header.dataLen && isRunning) {
                bytesRead = bis.read(frameData, totalBytesRead, header.dataLen - totalBytesRead);
                if (bytesRead == -1) {
                    Log.e(TAG, "Unexpected end of stream while reading frame data");
                    break;
                }
                totalBytesRead += bytesRead;
            }
            if (totalBytesRead != header.dataLen) {
                Log.e(TAG, "Incomplete frame data: expected " + header.dataLen + ", got " + totalBytesRead);
                break;
            }

            // 4. 回调帧数据
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Received frame: type=" + header.type + ", length=" + header.dataLen + ", timestamp="
                        + header.timestamp);
            }
            myFrameCallback.onFrameReceived(new MyFrame(header, frameData));
        }
    }

    public void stop() {
        isRunning = false;

        closeServerSocket();
        // Note: closeDumpFile() is called in serverThread's finally block to avoid race with processClientData()

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