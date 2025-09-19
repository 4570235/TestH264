package com.handley.myapplication.common;

// 帧封装类
public class MyFrame {

    public final MediaMessageHeader header;
    public final byte[] frameData;

    public MyFrame(MediaMessageHeader header, byte[] frameData) {
        this.header = header;
        this.frameData = frameData;
    }
}
