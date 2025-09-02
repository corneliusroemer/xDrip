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
    private static final int SAMPLE_RATE = 44100;
    private static final int TONE_DURATION_MS = 500;
    private static final int PAUSE_DURATION_MS = 200;
    private static final int FADE_MS = 5;                 // fade in/out per tone to avoid clicks

    // Glucose → frequency mapping
    private static final double MIN_FREQUENCY = 200.0;
    private static final double MAX_FREQUENCY = 800.0;
    private static final double MIN_GLUCOSE = 40.0;
    private static final double MAX_GLUCOSE = 300.0;

    private static final float TONE_VOLUME = 0.7f;

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
        final int toneSamples = msToSamples(toneMs);
        final int pauseSamples = msToSamples(pauseMs);

        // total samples = N * tone + (N-1) * pause
        int n = glucoseValues.length;
        int total = n * toneSamples + Math.max(0, (n - 1)) * pauseSamples;
        short[] out = new short[total];

        int writeIdx = 0;
        for (int i = 0; i < n; i++) {
            double f = mapGlucoseToFrequency(glucoseValues[i]);
            short[] tone = generateTonePcm(f, toneSamples);

            System.arraycopy(tone, 0, out, writeIdx, toneSamples);
            writeIdx += toneSamples;

            // pause (silence) except after last tone
            if (i < n - 1 && pauseSamples > 0) {
                // leaving array zeros is fine; advance index
                writeIdx += pauseSamples;
            }
        }
        return out;
    }

    private static short[] generateTonePcm(double freqHz, int samples) {
        // amplitude with headroom
        final double amp = TONE_VOLUME * 0.95 * Short.MAX_VALUE;
        final double twoPiOverFs = 2.0 * Math.PI / SAMPLE_RATE;

        // linear fade of FADE_MS at start and end
        final int fadeSamples = Math.min(msToSamples(FADE_MS), samples / 4);

        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            double env = 1.0;
            if (fadeSamples > 0) {
                if (i < fadeSamples) {
                    env = (i + 1) / (double) fadeSamples;                // fade in
                } else if (i >= samples - fadeSamples) {
                    env = (samples - i) / (double) fadeSamples;          // fade out
                }
            }
            double s = Math.sin(twoPiOverFs * freqHz * i);
            int v = (int) Math.round(amp * env * s);
            // clamp
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
            pcm[i] = (short) v;
        }
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
