package com.brouken.player.audio;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Media3 audio processor for headphone virtual surround (see {@link VirtualSurroundEngine}):
 * mono, stereo and multichannel (up to 7.1) 16 bit or float PCM in, binaural stereo out.
 *
 * <p>The room responses are synthesised on a background thread: until they are ready, and
 * whenever virtual surround is bypassed (switched off from the player, or no headphones), the
 * output is a plain stereo downmix. Switches crossfade.
 *
 * <p>The audio is processed in blocks of about 20 ms: each output frame is the processed input
 * frame with the same timestamp, so audio and video stay in sync.
 */
public final class VirtualSurroundAudioProcessor extends BaseAudioProcessor {

    private static final String TAG = "VirtualSurround";

    private static final ExecutorService BUILDER = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "VirtualSurroundBuilder");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    @Nullable
    private static HrirSet hrirs;

    private final Context context;
    private volatile RoomPreset preset;
    private volatile boolean userBypassed;
    private volatile boolean headphonesConnected;
    private volatile boolean processing;
    private volatile float dialogueBoost;

    // Playback thread
    private int channelCount = -1;
    private int sampleRate = -1;
    private int encoding = C.ENCODING_INVALID;
    private int blockSize;
    private float[][] block;
    private int fill;
    private float[] left;
    private float[] right;
    private float[] oldLeft;
    private float[] oldRight;
    private short[] shortScratch;
    private float[] floatScratch;
    @Nullable
    private VirtualSurroundEngine engine;
    @Nullable
    private Future<VirtualSurroundEngine> pendingEngine;
    @Nullable
    private RoomPreset pendingPreset;
    private boolean buildFailed;
    /** Crossfade from the previous engine (or the plain downmix) to {@link #engine}. */
    private boolean swapping;
    @Nullable
    private VirtualSurroundEngine swapFrom;

    public VirtualSurroundAudioProcessor(Context context, RoomPreset preset) {
        this.context = context.getApplicationContext();
        this.preset = preset;
        this.headphonesConnected = AudioRoutes.hasHeadphones(this.context);
    }

    // ---- Controls (any thread)

    public void setPreset(RoomPreset preset) {
        this.preset = preset;
    }

    public RoomPreset getPreset() {
        return preset;
    }

    /** Switches virtual surround off (true) or on (false), for comparison. */
    public void setUserBypassed(boolean bypassed) {
        userBypassed = bypassed;
    }

    public boolean isUserBypassed() {
        return userBypassed;
    }

    /** Brings the dialogue forward by this many dB (see {@link VirtualSurroundEngine#setDialogueBoost}). */
    public void setDialogueBoost(float decibels) {
        dialogueBoost = decibels;
    }

    /** Without headphones the output is the plain stereo mix. */
    public void setHeadphonesConnected(boolean connected) {
        headphonesConnected = connected;
    }

    public boolean isHeadphonesConnected() {
        return headphonesConnected;
    }

    /** Whether the audio currently playing is rendered in virtual surround. */
    public boolean isProcessing() {
        return processing;
    }

    private boolean isBypassed() {
        return userBypassed || !headphonesConnected;
    }

    // ---- AudioProcessor

    @Override
    public AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET;
        }
        if (!VirtualSurroundEngine.supportsChannelCount(inputAudioFormat.channelCount)
                || inputAudioFormat.sampleRate < 8000 || inputAudioFormat.sampleRate > 192000) {
            return AudioFormat.NOT_SET;
        }
        if (!AudioRoutes.hasHeadphones(context) && AudioRoutes.hasExternalOutput(context)) {
            // TV / AV receiver: keep its own (multichannel) sound
            return AudioFormat.NOT_SET;
        }
        return new AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding);
    }

    @Override
    protected void onFlush(StreamMetadata streamMetadata) {
        if (!isActive()) {
            processing = false;
            return;
        }
        final AudioFormat format = inputAudioFormat;
        if (format.channelCount != channelCount || format.sampleRate != sampleRate || format.encoding != encoding) {
            channelCount = format.channelCount;
            sampleRate = format.sampleRate;
            encoding = format.encoding;
            blockSize = VirtualSurroundEngine.blockSizeFor(sampleRate);
            block = new float[channelCount][blockSize];
            left = new float[blockSize];
            right = new float[blockSize];
            oldLeft = new float[blockSize];
            oldRight = new float[blockSize];
            // Input frames, or stereo output frames
            shortScratch = new short[blockSize * Math.max(2, channelCount)];
            floatScratch = new float[blockSize * Math.max(2, channelCount)];
            if (engine != null && (engine.getChannelCount() != channelCount || engine.getSampleRate() != sampleRate)) {
                engine = null;
            }
            buildFailed = false;
        }
        fill = 0;
        swapping = false;
        swapFrom = null;
        if (engine != null) {
            engine.setBypassed(isBypassed());
            engine.setDialogueBoost(dialogueBoost);
            engine.reset();
        }
        maybeBuildEngine();
    }

    @Override
    protected void onReset() {
        if (pendingEngine != null) {
            pendingEngine.cancel(false);
            pendingEngine = null;
        }
        engine = null;
        swapFrom = null;
        swapping = false;
        channelCount = -1;
        sampleRate = -1;
        encoding = C.ENCODING_INVALID;
        block = null;
        fill = 0;
        processing = false;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        final int inBytesPerFrame = inputAudioFormat.bytesPerFrame;
        int frames = inputBuffer.remaining() / inBytesPerFrame;
        if (frames == 0) {
            inputBuffer.position(inputBuffer.limit());
            return;
        }
        final int outFrames = (fill + frames) / blockSize * blockSize;
        final ByteBuffer output = outFrames > 0 ? replaceOutputBuffer(outFrames * outputAudioFormat.bytesPerFrame) : null;
        while (frames > 0) {
            final int count = Math.min(frames, blockSize - fill);
            readFrames(inputBuffer, count);
            fill += count;
            frames -= count;
            if (fill == blockSize) {
                processBlock();
                writeFrames(output, blockSize);
                fill = 0;
            }
        }
        // Drop a partial frame, if any (should not happen)
        inputBuffer.position(inputBuffer.limit());
        if (output != null) {
            output.flip();
        }
    }

    @Override
    protected void onQueueEndOfStream() {
        if (fill == 0 || block == null) {
            return;
        }
        final int frames = fill;
        for (float[] channel : block) {
            Arrays.fill(channel, frames, blockSize, 0f);
        }
        processBlock();
        // Keep the output not read yet, then add the last frames
        byte[] pending = null;
        if (hasPendingOutput()) {
            final ByteBuffer previous = super.getOutput();
            pending = new byte[previous.remaining()];
            previous.get(pending);
        }
        final int pendingBytes = pending != null ? pending.length : 0;
        final ByteBuffer output = replaceOutputBuffer(pendingBytes + frames * outputAudioFormat.bytesPerFrame);
        if (pending != null) {
            output.put(pending);
        }
        writeFrames(output, frames);
        output.flip();
        fill = 0;
    }

    // ---- Processing

    private void readFrames(ByteBuffer input, int frames) {
        final int channels = channelCount;
        final int samples = frames * channels;
        if (encoding == C.ENCODING_PCM_16BIT) {
            final ShortBuffer shorts = input.asShortBuffer();
            shorts.get(shortScratch, 0, samples);
            input.position(input.position() + samples * 2);
            for (int f = 0, i = 0; f < frames; f++) {
                for (int c = 0; c < channels; c++, i++) {
                    block[c][fill + f] = shortScratch[i] * (1f / 32768f);
                }
            }
        } else {
            final FloatBuffer floats = input.asFloatBuffer();
            floats.get(floatScratch, 0, samples);
            input.position(input.position() + samples * 4);
            for (int f = 0, i = 0; f < frames; f++) {
                for (int c = 0; c < channels; c++, i++) {
                    block[c][fill + f] = floatScratch[i];
                }
            }
        }
    }

    private void writeFrames(ByteBuffer output, int frames) {
        if (encoding == C.ENCODING_PCM_16BIT) {
            for (int f = 0, i = 0; f < frames; f++) {
                shortScratch[i++] = toShort(left[f]);
                shortScratch[i++] = toShort(right[f]);
            }
            output.asShortBuffer().put(shortScratch, 0, frames * 2);
            output.position(output.position() + frames * 4);
        } else {
            for (int f = 0, i = 0; f < frames; f++) {
                floatScratch[i++] = left[f];
                floatScratch[i++] = right[f];
            }
            output.asFloatBuffer().put(floatScratch, 0, frames * 2);
            output.position(output.position() + frames * 8);
        }
    }

    private static short toShort(float value) {
        final float scaled = value * 32767f;
        if (scaled >= 32767f) return Short.MAX_VALUE;
        if (scaled <= -32768f) return Short.MIN_VALUE;
        return (short) Math.round(scaled);
    }

    private void processBlock() {
        pickUpBuiltEngine();
        final VirtualSurroundEngine engine = this.engine;
        if (engine == null || engine.getPreset() != preset) {
            maybeBuildEngine();
        }
        final boolean bypassed = isBypassed();
        if (engine == null) {
            VirtualSurroundEngine.downmix(channelCount, block, blockSize, left, right);
            processing = false;
            return;
        }
        engine.setBypassed(bypassed);
        engine.setDialogueBoost(dialogueBoost);
        engine.process(block, left, right);
        if (swapping) {
            if (swapFrom != null) {
                swapFrom.setBypassed(bypassed);
                swapFrom.setDialogueBoost(dialogueBoost);
                swapFrom.process(block, oldLeft, oldRight);
            } else {
                VirtualSurroundEngine.downmix(channelCount, block, blockSize, oldLeft, oldRight);
            }
            for (int i = 0; i < blockSize; i++) {
                final double x = 0.5 * Math.PI * (i + 1) / blockSize;
                final float gNew = (float) Math.sin(x);
                final float gOld = (float) Math.cos(x);
                left[i] = gNew * left[i] + gOld * oldLeft[i];
                right[i] = gNew * right[i] + gOld * oldRight[i];
            }
            swapping = false;
            swapFrom = null;
        }
        processing = !bypassed;
    }

    private void pickUpBuiltEngine() {
        final Future<VirtualSurroundEngine> future = pendingEngine;
        if (future == null || !future.isDone()) {
            return;
        }
        pendingEngine = null;
        pendingPreset = null;
        VirtualSurroundEngine built = null;
        try {
            built = future.get();
        } catch (Exception e) {
            Log.e(TAG, "Virtual surround not available", e);
            buildFailed = true;
        }
        if (built == null || built.getChannelCount() != channelCount || built.getSampleRate() != sampleRate) {
            return;
        }
        built.setBypassed(isBypassed());
        built.setDialogueBoost(dialogueBoost);
        built.reset();
        swapFrom = engine;
        engine = built;
        swapping = true;
    }

    private void maybeBuildEngine() {
        final RoomPreset wanted = preset;
        if (buildFailed || channelCount < 1 || (engine != null && engine.getPreset() == wanted)) {
            return;
        }
        if (pendingEngine != null) {
            if (pendingPreset == wanted) {
                return;
            }
            // Another room was asked for meanwhile: the result of this build will be ignored
            pendingEngine.cancel(false);
        }
        final int channels = channelCount;
        final int rate = sampleRate;
        pendingPreset = wanted;
        pendingEngine = BUILDER.submit(() -> new VirtualSurroundEngine(getHrirs(context), wanted, channels, rate));
    }

    private static synchronized HrirSet getHrirs(Context context) throws IOException {
        if (hrirs == null) {
            try (InputStream in = context.getAssets().open(HrirSet.ASSET_NAME)) {
                hrirs = HrirSet.load(in);
            }
        }
        return hrirs;
    }
}
