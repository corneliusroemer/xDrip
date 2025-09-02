package com.eveningoutpost.dexdrip.utilitymodels;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.eveningoutpost.dexdrip.BestGlucose;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;

import java.util.ArrayList;
import java.util.List;

public class ToneSequencePlayer {

    private static final String TAG = "ToneSequencePlayer";

    // Defaults (overridden by preferences)
    private static final int    DEF_TONE_DURATION_MS = 500;
    private static final int    DEF_PAUSE_DURATION_MS= 200; // default gap; configurable
    private static final double DEF_MIN_FREQUENCY    = 200.0;
    private static final double DEF_MAX_FREQUENCY    = 800.0;
    private static final double DEF_MIN_GLUCOSE      = 40.0;
    private static final double DEF_MAX_GLUCOSE      = 300.0;

    private static final int SAMPLE_RATE   = 48000; // avoid SRC on most devices
    private static final int FADE_MS       = 100;    // longer edge taper
    private static final int LEAD_IN_MS    = 1000;     // silence before first tone
    private static final int LEAD_OUT_MS   = 30;     // optional
    private static final float DEF_TONE_VOLUME = 0.3f;  // extra headroom

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
                List<Double> gv = getRecentReadings();
                if (gv.isEmpty()) {
                    UserError.Log.d(TAG, "No readings");
                    return;
                }
                double[] values = gv.stream().mapToDouble(Double::doubleValue).toArray();
                // Let playToneSequence handle optional calibration prelude and timing
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
                // Test sequence: no calibration prelude
                playToneSequence(new double[]{65.0, 85.0, 120.0, 165.0, 220.0}, false);
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
        playToneSequence(glucoseValues, true);
    }

    private static void playToneSequence(double[] glucoseValues, boolean includeCalibration) {
        // Resolve runtime preferences
        final int toneDurationMs = Math.max(50, Pref.getInt("tone_duration_ms", DEF_TONE_DURATION_MS));
        final int pauseDurationMs = Math.max(0, Pref.getInt("tone_pause_ms", DEF_PAUSE_DURATION_MS));
        final int calibPauseAfterMs   = Math.max(0, Pref.getInt("tone_calib_pause_ms", 500));
        final boolean calibEnabled = includeCalibration && Pref.getBoolean("tone_calib_enabled", true);
        final double calibMultiplier = Math.max(1, Pref.getInt("tone_calib_multiplier", 10)) / 10.0; // tenths -> fractional

        // Split calibration vs readings to allow different timing
        List<Double> calValues = calibEnabled ? getCalibrationGlucoseValues() : new ArrayList<>();
        List<Double> readingValues = new ArrayList<>();
        for (double v : glucoseValues) readingValues.add(v);

        final int calToneMs = Math.min(10000, (int)Math.round(toneDurationMs * calibMultiplier));
        final int calPauseMs = Math.min(10000, (int)Math.round(pauseDurationMs * calibMultiplier));

        final float volume = getToneVolume();
        short[] pcm = buildSequencePcm(calValues, readingValues, calToneMs, calPauseMs, calibPauseAfterMs, toneDurationMs, pauseDurationMs, volume);

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

    private static short[] buildSequencePcm(List<Double> calValues, List<Double> readings, int calToneMs, int calPauseMs, int calibPauseAfterMs, int toneMs, int pauseMs, float volume) {
        final int calToneSamples  = msToSamples(calToneMs);
        final int calPauseSamples = msToSamples(calPauseMs);
        final int toneSamples  = msToSamples(toneMs);
        final int pauseSamples = msToSamples(pauseMs);
        final int leadIn       = msToSamples(LEAD_IN_MS);
        final int leadOut      = msToSamples(LEAD_OUT_MS);

        final int nCal = (calValues == null) ? 0 : calValues.size();
        final int nRead = (readings == null) ? 0 : readings.size();
        final int extraPause = (nCal > 0 && nRead > 0) ? msToSamples(calibPauseAfterMs) : 0;
        final int total = leadIn
                + nCal * calToneSamples + Math.max(0, nCal - 1) * calPauseSamples
                + extraPause
                + nRead * toneSamples + Math.max(0, nRead - 1) * pauseSamples
                + leadOut;
        short[] out = new short[total]; // zeros default: lead-in/out + pauses

        int writeIdx = leadIn;

        // calibration block
        for (int i = 0; i < nCal; i++) {
            double f = mapGlucoseToFrequency(calValues.get(i));
            short[] tone = generateTonePcmTukey(f, calToneSamples, /*alpha=*/0.5, volume);
            System.arraycopy(tone, 0, out, writeIdx, calToneSamples);
            writeIdx += calToneSamples;
            if (i < nCal - 1) writeIdx += calPauseSamples;
        }
        if (nCal > 0 && nRead > 0) writeIdx += extraPause;

        // readings block
        for (int i = 0; i < nRead; i++) {
            double f = mapGlucoseToFrequency(readings.get(i));
            short[] tone = generateTonePcmTukey(f, toneSamples, /*alpha=*/0.5, volume);
            System.arraycopy(tone, 0, out, writeIdx, toneSamples);
            writeIdx += toneSamples;
            if (i < nRead - 1) writeIdx += pauseSamples;
        }
        return out;
    }


    private static short[] generateTonePcmRaisedCos(double freqHz, int samples, float volume) {
        final int fade = Math.min(msToSamples(FADE_MS), Math.max(1, samples / 2));
        final double amp = volume * 0.8 * Short.MAX_VALUE; // extra headroom
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

    private static short[] generateTonePcmTukey(double freqHz, int samples, double alpha, float volume) {
        final double amp = volume * 0.85 * Short.MAX_VALUE;
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

    private static float getToneVolume() {
        int pct = Pref.getInt("tone_volume", (int)(DEF_TONE_VOLUME * 100));
        if (pct < 0) pct = 0; if (pct > 100) pct = 100;
        return pct / 100f;
    }


    private static int msToSamples(int ms) {
        return (int) Math.round(ms * (SAMPLE_RATE / 1000.0));
    }

    private static double mapGlucoseToFrequency(double glucose) {
        // Pull bounds from preferences and sanitize
        double minG = Pref.getInt("tone_min_glucose", (int) DEF_MIN_GLUCOSE);
        double maxG = Pref.getInt("tone_max_glucose", (int) DEF_MAX_GLUCOSE);
        if (maxG <= minG) maxG = minG + 1.0; // avoid divide-by-zero

        double minF = Pref.getInt("tone_min_frequency", (int) DEF_MIN_FREQUENCY);
        double maxF = Pref.getInt("tone_max_frequency", (int) DEF_MAX_FREQUENCY);
        if (maxF <= minF) maxF = minF + 1.0;

        double g = Math.max(minG, Math.min(maxG, glucose));
        double ratio = (g - minG) / (maxG - minG);
        if (Pref.getBoolean("tone_mapping_log_scale", false)) {
            // Exponential mapping: equal ratio in glucose maps to equal ratio in frequency (octave-like)
            double span = maxF / minF;
            return minF * Math.pow(span, ratio);
        } else {
            // Linear mapping
            return minF + ratio * (maxF - minF);
        }
    }

    private static List<Double> getRecentReadings() {
        final int count = Math.max(1, Math.min(36, Pref.getInt("tone_readings_count", 5)));
        final boolean oldestFirst = Pref.getBoolean("tone_order_oldest_first", false);

        List<Double> values = new ArrayList<>(count);
        try {
            List<BgReading> readings = BgReading.latest(count);
            if (readings != null) {
                // BgReading.latest returns newest first
                if (oldestFirst) {
                    for (int i = readings.size() - 1; i >= 0; i--) {
                        BgReading r = readings.get(i);
                        if (r.calculated_value > 0) values.add(r.calculated_value);
                    }
                } else {
                    for (BgReading r : readings) {
                        if (r.calculated_value > 0) values.add(r.calculated_value);
                    }
                }
            }
        } catch (Exception e) {
            UserError.Log.e(TAG, "getRecentReadings: " + e.getMessage(), e);
        }
        return values;
    }

    private static List<Double> getCalibrationGlucoseValues() {
        List<Double> cal = new ArrayList<>(3);
        try {
            int low  = Pref.getInt("tone_calib_low_glucose", 70);
            int high = Pref.getInt("tone_calib_high_glucose", 180);
            int max  = Pref.getInt("tone_calib_max_glucose", 400);
            // sanitize ordering
            if (high < low) high = low;
            if (max < high) max = high;
            cal.add((double) low);
            cal.add((double) high);
            cal.add((double) max);
        } catch (Exception ignore) {}
        return cal;
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
