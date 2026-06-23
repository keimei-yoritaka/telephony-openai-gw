package com.example.telephonygw.openai;

final class Pcm16Resampler {
    private Pcm16Resampler() {
    }

    static byte[] upsample(byte[] littleEndianPcm16, int sourceRateHz, int targetRateHz) {
        return resample(littleEndianPcm16, sourceRateHz, targetRateHz);
    }

    static byte[] downsample(byte[] littleEndianPcm16, int sourceRateHz, int targetRateHz) {
        return resample(littleEndianPcm16, sourceRateHz, targetRateHz);
    }

    static byte[] resample(byte[] littleEndianPcm16, int sourceRateHz, int targetRateHz) {
        if (sourceRateHz == targetRateHz) {
            return littleEndianPcm16.clone();
        }
        if (sourceRateHz <= 0 || targetRateHz <= 0) {
            throw new IllegalArgumentException("Sample rates must be positive");
        }
        if (littleEndianPcm16.length % 2 != 0) {
            throw new IllegalArgumentException("PCM16 payload length must be even");
        }

        int inputSamples = littleEndianPcm16.length / 2;
        if (inputSamples == 0) {
            return new byte[0];
        }
        int outputSamples = Math.max(1, (int) Math.round(inputSamples * (double) targetRateHz / sourceRateHz));
        byte[] output = new byte[outputSamples * 2];
        for (int outputSample = 0; outputSample < outputSamples; outputSample++) {
            double sourcePosition = outputSample * (double) sourceRateHz / targetRateHz;
            int leftIndex = (int) Math.floor(sourcePosition);
            int rightIndex = Math.min(leftIndex + 1, inputSamples - 1);
            double fraction = sourcePosition - leftIndex;
            int left = sample(littleEndianPcm16, leftIndex);
            int right = sample(littleEndianPcm16, rightIndex);
            int mixed = (int) Math.round(left + ((right - left) * fraction));
            writeSample(output, outputSample, mixed);
        }
        return output;
    }

    private static int sample(byte[] pcm, int sampleIndex) {
        int offset = sampleIndex * 2;
        int low = pcm[offset] & 0xFF;
        int high = pcm[offset + 1];
        return (short) ((high << 8) | low);
    }

    private static void writeSample(byte[] pcm, int sampleIndex, int value) {
        int clamped = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
        int offset = sampleIndex * 2;
        pcm[offset] = (byte) (clamped & 0xFF);
        pcm[offset + 1] = (byte) ((clamped >>> 8) & 0xFF);
    }
}
