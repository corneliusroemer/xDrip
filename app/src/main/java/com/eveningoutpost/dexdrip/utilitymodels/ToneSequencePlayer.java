package com.eveningoutpost.dexdrip.utilitymodels;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.eveningoutpost.dexdrip.BestGlucose;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.UserError;

import java.util.ArrayList;
import java.util.List;

public class ToneSequencePlayer {

    private static final String TAG = "ToneSequencePlayer";

    // Audio configuration
    private static final int TONE_DURATION_MS = 500;
    private static final int PAUSE_DURATION_MS = 200;

    // Glucose → frequency mapping
    private static final double MIN_FREQUENCY = 200.0;
    private static final double MAX_FREQUENCY = 800.0;
    private static final double MIN_GLUCOSE = 40.0;
    private static final double MAX_GLUCOSE = 300.0;

    private static final int SAMPLE_RATE   = 48000; // avoid SRC on most devices
    private static final int FADE_MS       = 100;    // longer edge taper
    private static final int LEAD_IN_MS    = 1000;     // silence before first tone
    private static final int LEAD_OUT_MS   = 30;     // optional
    private static final float TONE_VOLUME = 0.3f;  // extra headroom

    private static volatile boolean isPlaying = false;
    private static AudioTrack currentAudioTrack = null;

    // Public API ---------------------------------------------------------------

    public static void playReadingsSequence() {
        if (isPlaying) {
            UserError.Log.d(TAG, "Already playing");
            return;
        }
        new Thread(() -> {
            try {
                List<Double> gv = getLastFiveReadings();
                if (gv.isEmpty()) {
                    UserError.Log.d(TAG, "No readings");
                    return;
                }
                double[] values = gv.stream().mapToDouble(Double::doubleValue).toArray();
                playToneSequence(values);
            } catch (Exception t) {
                UserError.Log.e(TAG, "playReadingsSequence error: " + t.getMessage(), t);
                cleanupCurrent();
            }
        }, "ToneSequencePlayer").start();
    }

    public static void playTestSequence() {
        if (isPlaying) {
            UserError.Log.d(TAG, "Already playing");
            return;
        }
        new Thread(() -> {
            try {
                playToneSequence(new double[]{65.0, 85.0, 120.0, 165.0, 220.0});
            } catch (Exception t) {
                UserError.Log.e(TAG, "playTestSequence error: " + t.getMessage(), t);
                cleanupCurrent();
            }
        }, "ToneSequencePlayerTest").start();
    }

    public static void stopPlaying() {
        isPlaying = false;
        cleanupCurrent();
    }

    public static boolean isPlaying() {
        return isPlaying;
    }

    public static double getFrequencyForGlucose(double glucose) {
        return mapGlucoseToFrequency(glucose);
    }

    // Internals ---------------------------------------------------------------

    private static void playToneSequence(double[] glucoseValues) {
        // Build entire PCM sequence once
        short[] pcm = buildSequencePcm(glucoseValues, TONE_DURATION_MS, PAUSE_DURATION_MS);

        // bytes needed for MODE_STATIC buffer
        int totalBytes = pcm.length * 2;

        isPlaying = true;
        UserError.Log.d(TAG, "PCM frames=" + pcm.length + " bytes=" + totalBytes);

        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(totalBytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();

            if (track.getState() != AudioTrack.STATE_INITIALIZED && track.getState() != AudioTrack.STATE_NO_STATIC_DATA ) {
                throw new IllegalStateException("AudioTrack init failed, state=" + track.getState());
            }

            currentAudioTrack = track;

            // Load entire buffer once
            int written = track.write(pcm, 0, pcm.length);
            if (written != pcm.length) {
                UserError.Log.d(TAG, "Short write to static buffer: " + written + " / " + pcm.length);
            }

            // Set marker to auto-cleanup when playback completes
            final int totalFrames = pcm.length; // mono: 1 sample == 1 frame
            track.setNotificationMarkerPosition(Math.max(0, totalFrames - 1));
            track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
                @Override public void onMarkerReached(AudioTrack audioTrack) {
                    UserError.Log.d(TAG, "Playback completed");
                    isPlaying = false;
                    cleanupCurrent(); // stop + release
                }
                @Override public void onPeriodicNotification(AudioTrack audioTrack) { /*unused*/ }
            });

            track.play();

        } catch (Exception t) {
            UserError.Log.e(TAG, "Failed to play sequence: " + t.getMessage(), t);
            isPlaying = false;
            if (track != null) {
                try { track.release(); } catch (Throwable ignore) {}
            }
            currentAudioTrack = null;
        }
    }

    private static short[] buildSequencePcm(double[] glucoseValues, int toneMs, int pauseMs) {
        final int toneSamples  = msToSamples(toneMs);
        final int pauseSamples = msToSamples(pauseMs);
        final int leadIn       = msToSamples(LEAD_IN_MS);
        final int leadOut      = msToSamples(LEAD_OUT_MS);

        int n = glucoseValues.length;
        int total = leadIn + n * toneSamples + Math.max(0, (n - 1)) * pauseSamples + leadOut;
        short[] out = new short[total]; // zeros default: lead-in/out + pauses

        int writeIdx = leadIn;
        for (int i = 0; i < n; i++) {
            double f = mapGlucoseToFrequency(glucoseValues[i]);
            short[] tone = generateTonePcmTukey(f, toneSamples, /*alpha=*/0.5); // 50% tapered edges
            System.arraycopy(tone, 0, out, writeIdx, toneSamples);
            writeIdx += toneSamples;
            if (i < n - 1) writeIdx += pauseSamples; // keep zeros
        }
        return out;
    }


    private static short[] generateTonePcmRaisedCos(double freqHz, int samples) {
        final int fade = Math.min(msToSamples(FADE_MS), Math.max(1, samples / 2));
        final double amp = TONE_VOLUME * 0.8 * Short.MAX_VALUE; // extra headroom
        final double w = 2.0 * Math.PI * freqHz / SAMPLE_RATE;

        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            double env = 1.0;

            // fade-in: 0 -> 1 using half-cosine (hits exactly 0 at i=0)
            if (i < fade) {
                double x = (double) i / (double) fade;          // [0,1)
                env *= 0.5 - 0.5 * Math.cos(Math.PI * x);       // 0..~1
            }
            // fade-out: 1 -> 0 (hits exactly 0 at the last sample)
            if (i >= samples - fade) {
                double x = (double) (samples - 1 - i) / (double) fade; // [0,1]
                env *= 0.5 - 0.5 * Math.cos(Math.PI * x);       // ~1..0; at i=last -> 0
            }

            double s = Math.sin(w * i) * env;
            int v = (int) Math.round(amp * s);
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
            pcm[i] = (short) v;
        }

        // enforce exact zeros at boundaries
        pcm[0] = 0;
        pcm[samples - 1] = 0;
        return pcm;
    }

    private static short[] generateTonePcmTukey(double freqHz, int samples, double alpha) {
        final double amp = TONE_VOLUME * 0.85 * Short.MAX_VALUE;
        final double w = 2.0 * Math.PI * freqHz / SAMPLE_RATE;

        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            double t = (double) i / (samples - 1); // [0,1]
            double env;
            if (alpha <= 0.0) {
                // pure Hann
                env = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * t));
            } else if (alpha >= 1.0) {
                env = 1.0;
            } else {
                double edge = alpha / 2.0;
                if (t < edge) {
                    double x = t / edge;                // [0,1]
                    env = 0.5 * (1.0 - Math.cos(Math.PI * x));
                } else if (t <= 1.0 - edge) {
                    env = 1.0;
                } else {
                    double x = (t - 1.0 + edge) / edge; // [0,1]
                    env = 0.5 * (1.0 + Math.cos(Math.PI * x));
                }
            }

            double s = Math.sin(w * i) * env;
            int v = (int) Math.round(amp * s);
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
            pcm[i] = (short) v;
        }
        // exact zeros at boundaries
        pcm[0] = 0;
        pcm[samples - 1] = 0;
        return pcm;
    }


    private static int msToSamples(int ms) {
        return (int) Math.round(ms * (SAMPLE_RATE / 1000.0));
    }

    private static double mapGlucoseToFrequency(double glucose) {
        double g = Math.max(MIN_GLUCOSE, Math.min(MAX_GLUCOSE, glucose));
        double ratio = (g - MIN_GLUCOSE) / (MAX_GLUCOSE - MIN_GLUCOSE);
        return MIN_FREQUENCY + ratio * (MAX_FREQUENCY - MIN_FREQUENCY);
    }

    private static List<Double> getLastFiveReadings() {
        List<Double> values = new ArrayList<>();
        try {
            BestGlucose.DisplayGlucose dg = BestGlucose.getDisplayGlucose();
            if (dg != null && dg.mgdl > 0) values.add(dg.mgdl);

            List<BgReading> readings = BgReading.latest(5);
            if (readings != null) {
                for (BgReading r : readings) {
                    if (values.size() >= 5) break;
                    if (r.calculated_value > 0) {
                        if (values.isEmpty() || Math.abs(values.get(0) - r.calculated_value) > 1.0) {
                            values.add(r.calculated_value);
                        }
                    }
                }
            }
        } catch (Exception e) {
            UserError.Log.e(TAG, "getLastFiveReadings: " + e.getMessage());
        }
        return values;
    }

    private static void cleanupCurrent() {
        AudioTrack at = currentAudioTrack;
        currentAudioTrack = null;
        if (at != null) {
            try {
                if (at.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    at.stop();
                }
            } catch (Throwable ignore) {}
            try { at.release(); } catch (Throwable ignore) {}
        }
    }
}
