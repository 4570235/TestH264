package com.handley.myapplication.common;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MediaMessageHeader {

    public static final int MAGIC = 0x0133C96C;
    public static final int SIZE = 21; // 4+1+8+4+4 bytes

    // 媒体类型常量
    public static final byte PCM = 0;
    public static final byte OPUS = 1;
    public static final byte H264 = 2;
    public static final byte H265 = 3;
    public static final byte VP8 = 4;

    public int magic;
    public byte type;
    public long timestamp;
    public int rotation;
    public int dataLen;

    // 从字节数组解析对象（小端序）
    public static MediaMessageHeader parse(byte[] data) {
        if (data == null || data.length < SIZE) {
            throw new IllegalArgumentException("Invalid header data");
        }

        MediaMessageHeader header = new MediaMessageHeader();
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        header.magic = buffer.getInt();
        header.type = buffer.get();
        header.timestamp = buffer.getLong();
        header.rotation = buffer.getInt();
        header.dataLen = buffer.getInt();

        return header;
    }

    // 此实现不使用ByteBuffer，而是直接使用位运算，结果相同。
    public static MediaMessageHeader parse0(byte[] data) {
        MediaMessageHeader header = new MediaMessageHeader();

        // 按小端字节序解析
        header.magic = (data[3] << 24) | ((data[2] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
        header.type = data[4];

        // 解析64位时间戳
        header.timestamp =
                ((long) (data[12] & 0xFF) << 56) |
                        ((long) (data[11] & 0xFF) << 48) |
                        ((long) (data[10] & 0xFF) << 40) |
                        ((long) (data[9] & 0xFF) << 32) |
                        ((long) (data[8] & 0xFF) << 24) |
                        ((long) (data[7] & 0xFF) << 16) |
                        ((long) (data[6] & 0xFF) << 8) |
                        ((long) (data[5] & 0xFF));

        header.rotation =
                ((data[16] & 0xFF) << 24) |
                        ((data[15] & 0xFF) << 16) |
                        ((data[14] & 0xFF) << 8) |
                        (data[13] & 0xFF);

        header.dataLen =
                ((data[20] & 0xFF) << 24) |
                        ((data[19] & 0xFF) << 16) |
                        ((data[18] & 0xFF) << 8) |
                        (data[17] & 0xFF);

        return header;
    }

    // 将对象序列化为字节数组（小端序）
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(magic);
        buffer.put(type);
        buffer.putLong(timestamp);
        buffer.putInt(rotation);
        buffer.putInt(dataLen);

        return buffer.array();
    }


}