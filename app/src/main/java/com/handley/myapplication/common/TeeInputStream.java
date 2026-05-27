package com.handley.myapplication.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 包装 InputStream，每次 read 到的数据会同时写入 teeOut。
 * 这样 dump 文件内容和 socket 收到的原始字节完全一致。
 */
public class TeeInputStream extends InputStream {

    private final InputStream in;
    private final OutputStream teeOut;
    private boolean closed = false;

    public TeeInputStream(InputStream in, OutputStream teeOut) {
        this.in = in;
        this.teeOut = teeOut;
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b != -1 && teeOut != null) {
            teeOut.write(b);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n > 0 && teeOut != null) {
            teeOut.write(b, off, n);
        }
        return n;
    }

    @Override
    public int available() throws IOException {
        return in.available();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            in.close();
        }
    }
}
