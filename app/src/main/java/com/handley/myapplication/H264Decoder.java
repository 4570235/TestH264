package com.handley.myapplication;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class H264Decoder {

    private static final String TAG = "H264Decoder";
    private static final long TIMEOUT_US = 10000;
    private static final int BUFFER_SIZE = 1024 * 1024; // 1MB buffer

    private MediaCodec mediaCodec;
    private boolean isRunning = false;
    private Thread decodeThread;
    private boolean isFirstFrame = true;
    private byte[] sps;
    private byte[] pps;
    private Surface outputSurface;

    public void initDecoder(Surface surface) {
        outputSurface = surface;
    }

    public void startDecoding(InputStream inputStream) {
        if (isRunning) return;

        isRunning = true;
        decodeThread = new Thread(() -> {
            try {
                decodeH264Stream(inputStream);
            } catch (Exception e) {
                Log.e(TAG, "Decoding error", e);
            } finally {
                release();
            }
        });
        decodeThread.start();
    }

    private void decodeH264Stream(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        long presentationTimeUs = 0;

        while (isRunning && (bytesRead = inputStream.read(buffer)) != -1) {
            int offset = 0;

            while (offset < bytesRead) {
                int startCode = findStartCode(buffer, offset, bytesRead);
                if (startCode == -1) break;

                // Determine start code length (3 or 4 bytes)
                int startCodeLength = 3;
                if (startCode + 3 < bytesRead &&
                        buffer[startCode + 2] == 0x00 &&
                        buffer[startCode + 3] == 0x01) {
                    startCodeLength = 4;
                }

                int nalStart = startCode + startCodeLength;
                if (nalStart >= bytesRead) break;

                // Extract NAL type (lower 5 bits of first byte)
                byte nalType = (byte) (buffer[nalStart] & 0x1F);
                Log.d(TAG, "Found NAL unit type: " + nalType);

                // Find next start code
                int nextStartCode = findStartCode(buffer, startCode + startCodeLength, bytesRead);
                int nalEnd = (nextStartCode == -1) ? bytesRead : nextStartCode;

                // Calculate NAL unit size (excluding start code)
                int nalSize = nalEnd - nalStart;
                if (nalSize <= 0) break;

                // Copy NAL unit data (excluding start code)
                byte[] frameData = new byte[nalSize];
                System.arraycopy(buffer, nalStart, frameData, 0, nalSize);

                // Process NAL unit
                processNalUnit(frameData, nalType, presentationTimeUs);

                // Update offset and timestamp
                offset = nalEnd;
                presentationTimeUs += 33333; // Adjust based on actual frame rate
            }
        }
    }

    private void processNalUnit(byte[] frameData, byte nalType, long presentationTimeUs) {
        switch (nalType) {
            case 7: // SPS
                sps = frameData;
                Log.d(TAG, "SPS found, length: " + sps.length);
                break;

            case 8: // PPS
                pps = frameData;
                Log.d(TAG, "PPS found, length: " + pps.length);
                break;

            case 5: // IDR frame
                Log.d(TAG, "IDR frame found");
                if (sps != null && pps != null && isFirstFrame) {
                    configureDecoderWithSpsPps();
                    isFirstFrame = false;
                }
                // Fall through to feed frame to decoder

            case 1: // Non-IDR frame
            case 6: // SEI
            default: // Other frame types
                if (!isFirstFrame) {
                    feedDecoder(frameData, presentationTimeUs);
                } else {
                    Log.w(TAG, "Skipping frame before decoder initialization");
                }
                break;
        }
    }

    private void configureDecoderWithSpsPps() {
        try {
            // Parse SPS to get resolution
            int width = parseWidthFromSps(sps);
            int height = parseHeightFromSps(sps);
            Log.d(TAG, "Configuring decoder with resolution: " + width + "x" + height);

            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    width,
                    height
            );

            // Set SPS/PPS (without start code)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));

            // Create and configure decoder
            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mediaCodec.configure(format, outputSurface, null, 0);
            mediaCodec.start();

            Log.d(TAG, "Decoder initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to configure decoder", e);
            isFirstFrame = true; // Allow retry on next IDR frame
        }
    }

    private void feedDecoder(byte[] frameData, long presentationTimeUs) {
        try {
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(TIMEOUT_US);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(frameData);
                    mediaCodec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            frameData.length,
                            presentationTimeUs,
                            0
                    );
                }
            }

            // Process output
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);

            while (outputBufferIndex >= 0) {
                mediaCodec.releaseOutputBuffer(outputBufferIndex, true);
                outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Decoder error during frame processing", e);
        }
    }

    private int findStartCode(byte[] data, int start, int end) {
        for (int i = start; i <= end - 4; i++) {
            // Check for 3-byte start code (0x000001)
            if (i <= end - 3 &&
                    data[i] == 0x00 &&
                    data[i+1] == 0x00 &&
                    data[i+2] == 0x01) {
                return i;
            }

            // Check for 4-byte start code (0x00000001)
            if (i <= end - 4 &&
                    data[i] == 0x00 &&
                    data[i+1] == 0x00 &&
                    data[i+2] == 0x00 &&
                    data[i+3] == 0x01) {
                return i;
            }
        }
        return -1;
    }

    private int parseWidthFromSps(byte[] sps) {
        // Simplified SPS parsing - in real projects use a proper parser
        // This should extract width from SPS NAL unit
        return 540; // Default value
    }

    private int parseHeightFromSps(byte[] sps) {
        // Simplified SPS parsing
        return 960; // Default value
    }

    public void release() {
        isRunning = false;
        if (decodeThread != null) {
            try {
                decodeThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            decodeThread = null;
        }

        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "MediaCodec release error", e);
            }
            mediaCodec = null;
        }

        // Reset state for potential reuse
        isFirstFrame = true;
        sps = null;
        pps = null;
    }
}