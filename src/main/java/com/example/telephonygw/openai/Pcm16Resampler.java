package com.example.telephonygw.openai;

final class Pcm16Resampler {
    private Pcm16Resampler() {
    }

    static byte[] upsample(byte[] littleEndianPcm16, int sourceRateHz, int targetRateHz) {
        if (sourceRateHz == targetRateHz) {
            return littleEndianPcm16.clone();
        }
        if (sourceRateHz <= 0 || targetRateHz <= 0) {
            throw new IllegalArgumentException("Sample rates must be positive");
        }
        if (littleEndianPcm16.length % 2 != 0) {
            throw new IllegalArgumentException("PCM16 payload length must be even");
        }
        if (targetRateHz % sourceRateHz != 0) {
            throw new IllegalArgumentException(
                    "Only integer PCM16 upsampling is supported: " + sourceRateHz + " -> " + targetRateHz);
        }

        int factor = targetRateHz / sourceRateHz;
        byte[] output = new byte[littleEndianPcm16.length * factor];
        int outputIndex = 0;
        for (int inputIndex = 0; inputIndex < littleEndianPcm16.length; inputIndex += 2) {
            byte low = littleEndianPcm16[inputIndex];
            byte high = littleEndianPcm16[inputIndex + 1];
            for (int repeat = 0; repeat < factor; repeat++) {
                output[outputIndex++] = low;
                output[outputIndex++] = high;
            }
        }
        return output;
    }

    static byte[] downsample(byte[] littleEndianPcm16, int sourceRateHz, int targetRateHz) {
        if (sourceRateHz == targetRateHz) {
            return littleEndianPcm16.clone();
        }
        if (sourceRateHz <= 0 || targetRateHz <= 0) {
            throw new IllegalArgumentException("Sample rates must be positive");
        }
        if (littleEndianPcm16.length % 2 != 0) {
            throw new IllegalArgumentException("PCM16 payload length must be even");
        }
        if (sourceRateHz % targetRateHz != 0) {
            throw new IllegalArgumentException(
                    "Only integer PCM16 downsampling is supported: " + sourceRateHz + " -> " + targetRateHz);
        }

        int factor = sourceRateHz / targetRateHz;
        int inputSamples = littleEndianPcm16.length / 2;
        int outputSamples = inputSamples / factor;
        byte[] output = new byte[outputSamples * 2];
        int outputIndex = 0;
        for (int sample = 0; sample < outputSamples; sample++) {
            int inputIndex = sample * factor * 2;
            output[outputIndex++] = littleEndianPcm16[inputIndex];
            output[outputIndex++] = littleEndianPcm16[inputIndex + 1];
        }
        return output;
    }
}
