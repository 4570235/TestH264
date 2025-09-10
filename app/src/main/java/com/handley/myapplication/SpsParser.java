package com.handley.myapplication;

import android.util.Log;
import java.nio.ByteBuffer;

public class SpsParser {

    private static final String TAG = "SpsParser";

    // 指数哥伦布编码解码器
    private static class ExpGolombDecoder {
        private final byte[] data;
        private int offset; // 字节偏移
        private int bitOffset; // 位偏移 (0-7)
        private int currentByte;

        public ExpGolombDecoder(byte[] data) {
            this.data = data;
            this.offset = 0;
            this.bitOffset = 0;
            if (data.length > 0) {
                currentByte = data[0] & 0xFF;
            }
        }

        // 读取一个无符号指数哥伦布编码值
        public int readUE() {
            int leadingZeroBits = 0;
            while (readBit() == 0) {
                leadingZeroBits++;
            }

            if (leadingZeroBits == 0) {
                return 0;
            }

            int value = (1 << leadingZeroBits) - 1;
            value += readBits(leadingZeroBits);
            return value;
        }

        // 读取一个有符号指数哥伦布编码值
        public int readSE() {
            int value = readUE();
            if ((value & 0x01) != 0) {
                return (value + 1) >> 1;
            } else {
                return -(value >> 1);
            }
        }

        // 读取一个比特
        private int readBit() {
            if (offset >= data.length) {
                return 0; // 超出范围返回0
            }

            int bit = (currentByte >> (7 - bitOffset)) & 1;
            bitOffset++;

            if (bitOffset >= 8) {
                bitOffset = 0;
                offset++;
                if (offset < data.length) {
                    currentByte = data[offset] & 0xFF;
                }
            }

            return bit;
        }

        // 读取多个比特
        private int readBits(int numBits) {
            int value = 0;
            for (int i = 0; i < numBits; i++) {
                value = (value << 1) | readBit();
            }
            return value;
        }
    }

    // 解析SPS并返回宽高
    public static int[] parseSps(byte[] sps) {
        try {
            // 跳过NAL头 (1字节) 和可能的防竞争字节
            int start = 0;
            if (sps.length > 0) {
                // 跳过NAL头 (类型为7)
                start = 1;

                // 跳过可能的防竞争字节 (0x03)
                if (sps.length > 3 && sps[1] == 0x00 && sps[2] == 0x00 && sps[3] == 0x03) {
                    start = 4;
                }
            }

            // 创建解码器
            byte[] spsData = new byte[sps.length - start];
            System.arraycopy(sps, start, spsData, 0, spsData.length);
            ExpGolombDecoder decoder = new ExpGolombDecoder(spsData);

            // 解析SPS参数
            int profileIdc = decoder.readBits(8);
            decoder.readBits(1); // constraint_set0_flag
            decoder.readBits(1); // constraint_set1_flag
            decoder.readBits(1); // constraint_set2_flag
            decoder.readBits(1); // constraint_set3_flag
            decoder.readBits(4); // reserved_zero_4bits
            int levelIdc = decoder.readBits(8);
            decoder.readUE(); // seq_parameter_set_id

            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 ||
                    profileIdc == 244 || profileIdc == 44 || profileIdc == 83 ||
                    profileIdc == 86 || profileIdc == 118 || profileIdc == 128) {

                int chromaFormatIdc = decoder.readUE();
                if (chromaFormatIdc == 3) {
                    decoder.readBits(1); // separate_colour_plane_flag
                }

                decoder.readUE(); // bit_depth_luma_minus8
                decoder.readUE(); // bit_depth_chroma_minus8
                decoder.readBits(1); // qpprime_y_zero_transform_bypass_flag

                int seqScalingMatrixPresentFlag = decoder.readBits(1);
                if (seqScalingMatrixPresentFlag == 1) {
                    int numScalingLists = (chromaFormatIdc != 3) ? 8 : 12;
                    for (int i = 0; i < numScalingLists; i++) {
                        int seqScalingListPresentFlag = decoder.readBits(1);
                        if (seqScalingListPresentFlag == 1) {
                            skipScalingList(decoder, i < 6 ? 16 : 64);
                        }
                    }
                }
            }

            decoder.readUE(); // log2_max_frame_num_minus4
            int picOrderCntType = decoder.readUE();

            if (picOrderCntType == 0) {
                decoder.readUE(); // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                decoder.readBits(1); // delta_pic_order_always_zero_flag
                decoder.readSE(); // offset_for_non_ref_pic
                decoder.readSE(); // offset_for_top_to_bottom_field
                int numRefFramesInPicOrderCntCycle = decoder.readUE();
                for (int i = 0; i < numRefFramesInPicOrderCntCycle; i++) {
                    decoder.readSE(); // offset_for_ref_frame[i]
                }
            }

            decoder.readUE(); // max_num_ref_frames
            decoder.readBits(1); // gaps_in_frame_num_value_allowed_flag

            // 解析宽高参数
            int picWidthInMbsMinus1 = decoder.readUE();
            int picHeightInMapUnitsMinus1 = decoder.readUE();
            int frameMbsOnlyFlag = decoder.readBits(1);

            int width = (picWidthInMbsMinus1 + 1) * 16;
            int height = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag);

            // 检查裁剪参数
            int frameCroppingFlag = decoder.readBits(1);
            if (frameCroppingFlag == 1) {
                int frameCropLeftOffset = decoder.readUE();
                int frameCropRightOffset = decoder.readUE();
                int frameCropTopOffset = decoder.readUE();
                int frameCropBottomOffset = decoder.readUE();

                // 应用裁剪
                width -= (frameCropLeftOffset + frameCropRightOffset) * 2;
                height -= (frameCropTopOffset + frameCropBottomOffset) * 2;
            }

            return new int[]{width, height};
        } catch (Exception e) {
            Log.e(TAG, "Error parsing SPS: " + e.getMessage());
            return new int[]{0, 0};
        }
    }

    // 跳过缩放列表数据
    private static void skipScalingList(ExpGolombDecoder decoder, int size) {
        int lastScale = 8;
        int nextScale = 8;

        for (int j = 0; j < size; j++) {
            if (nextScale != 0) {
                int deltaScale = decoder.readSE();
                nextScale = (lastScale + deltaScale + 256) % 256;
            }

            lastScale = (nextScale == 0) ? lastScale : nextScale;
        }
    }
}