package com.brouken.player.dovi;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SniffFailure;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.SubtitleParser;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link ExtractorsFactory} wrapper that converts single-track Dolby Vision profile 7 video
 * (UHD Blu-ray, MEL or FEL, e.g. MakeMKV remuxes) to profile 8.1 on the fly, while it is being
 * extracted, so it can be played by Dolby Vision decoders that only support profile 8 (most
 * phones and tablets).
 *
 * <p>For each access unit the enhancement layer NAL units are dropped and the RPU is rewritten as a
 * profile 8.1 RPU; the track format is advertised as {@code dvhe.08.xx}. Devices without a Dolby
 * Vision decoder fall back to plain HEVC (HDR10) as for any profile 8.1 content.
 */
public final class DoviConvertingExtractorsFactory implements ExtractorsFactory {

    private static final String TAG = "DoviP7toP81";

    /** Appended to the label of converted video tracks. */
    public static final String LABEL_SUFFIX = "Dolby Vision 7 → 8.1";

    /** Converter of the most recently started profile 7 track, for diagnostics. */
    @Nullable
    private static volatile DoviSampleConverter lastActiveConverter;

    private final ExtractorsFactory delegate;

    @Nullable
    public static DoviSampleConverter getLastActiveConverter() {
        return lastActiveConverter;
    }

    public DoviConvertingExtractorsFactory(ExtractorsFactory delegate) {
        this.delegate = delegate;
    }

    public static boolean isConvertedFormat(@Nullable Format format) {
        return format != null && format.label != null && format.label.endsWith(LABEL_SUFFIX);
    }

    @SuppressWarnings("deprecation")
    @Override
    public ExtractorsFactory experimentalSetTextTrackTranscodingEnabled(boolean textTrackTranscodingEnabled) {
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled);
        return this;
    }

    @Override
    public ExtractorsFactory setSubtitleParserFactory(SubtitleParser.Factory subtitleParserFactory) {
        delegate.setSubtitleParserFactory(subtitleParserFactory);
        return this;
    }

    @Override
    public ExtractorsFactory setParseHagcMetadata(boolean parseHagcMetadata) {
        delegate.setParseHagcMetadata(parseHagcMetadata);
        return this;
    }

    @Override
    public Extractor[] createExtractors() {
        return wrap(delegate.createExtractors());
    }

    @Override
    public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
        return wrap(delegate.createExtractors(uri, responseHeaders));
    }

    private static Extractor[] wrap(Extractor[] extractors) {
        final Extractor[] wrapped = new Extractor[extractors.length];
        for (int i = 0; i < extractors.length; i++) {
            wrapped[i] = new ConvertingExtractor(extractors[i]);
        }
        return wrapped;
    }

    private static final class ConvertingExtractor implements Extractor {

        private final Extractor delegate;
        private final List<ConvertingTrackOutput> trackOutputs = new ArrayList<>();

        ConvertingExtractor(Extractor delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean sniff(ExtractorInput input) throws IOException {
            return delegate.sniff(input);
        }

        @Override
        public List<SniffFailure> getSniffFailureDetails() {
            return delegate.getSniffFailureDetails();
        }

        @Override
        public void init(ExtractorOutput output) {
            delegate.init(new ConvertingExtractorOutput(output, trackOutputs));
        }

        @Override
        public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
            return delegate.read(input, seekPosition);
        }

        @Override
        public void seek(long position, long timeUs) {
            for (ConvertingTrackOutput trackOutput : trackOutputs) {
                trackOutput.reset();
            }
            delegate.seek(position, timeUs);
        }

        @Override
        public void release() {
            for (ConvertingTrackOutput trackOutput : trackOutputs) {
                trackOutput.logStats();
            }
            delegate.release();
        }

        @Override
        public Extractor getUnderlyingImplementation() {
            return delegate.getUnderlyingImplementation();
        }
    }

    private static final class ConvertingExtractorOutput implements ExtractorOutput {

        private final ExtractorOutput delegate;
        private final List<ConvertingTrackOutput> trackOutputs;

        ConvertingExtractorOutput(ExtractorOutput delegate, List<ConvertingTrackOutput> trackOutputs) {
            this.delegate = delegate;
            this.trackOutputs = trackOutputs;
        }

        @Override
        public TrackOutput track(int id, int type) {
            final TrackOutput trackOutput = delegate.track(id, type);
            if (type != C.TRACK_TYPE_VIDEO) {
                return trackOutput;
            }
            final ConvertingTrackOutput converting = new ConvertingTrackOutput(trackOutput);
            trackOutputs.add(converting);
            return converting;
        }

        @Override
        public void endTracks() {
            delegate.endTracks();
        }

        @Override
        public void seekMap(SeekMap seekMap) {
            delegate.seekMap(seekMap);
        }
    }

    /**
     * Buffers the data of each sample of a profile 7 track, converts it when its metadata arrives
     * and forwards it. Other tracks are passed through untouched.
     */
    static final class ConvertingTrackOutput implements TrackOutput {
        // Package-private so that it can be tested with the real Media3 classes.

        private final TrackOutput delegate;
        private final DoviSampleConverter converter = new DoviSampleConverter();
        private final ParsableByteArray scratch = new ParsableByteArray();
        private final byte[] sizePrefix = new byte[4];

        private boolean active;
        private byte[] pending = new byte[512 * 1024];
        private int pendingLength;
        private boolean pendingHasOtherParts;

        ConvertingTrackOutput(TrackOutput delegate) {
            this.delegate = delegate;
        }

        void reset() {
            pendingLength = 0;
            pendingHasOtherParts = false;
        }

        @Override
        public void durationUs(long durationUs) {
            delegate.durationUs(durationUs);
        }

        @Override
        public void format(Format format) {
            final boolean convert = MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)
                    && DoviSampleConverter.isProfile7Codecs(format.codecs);
            if (!convert) {
                if (active) {
                    flushPendingUnchanged();
                    active = false;
                }
                delegate.format(format);
                return;
            }
            active = true;
            lastActiveConverter = converter;
            final String label = format.label == null ? LABEL_SUFFIX : format.label + " - " + LABEL_SUFFIX;
            final Format converted = format.buildUpon()
                    .setCodecs(DoviSampleConverter.convertCodecsString(format.codecs))
                    .setInitializationData(DoviSampleConverter.filterInitializationData(format.initializationData))
                    .setLabel(label)
                    .build();
            Log.i(TAG, "Converting Dolby Vision " + format.codecs + " to " + converted.codecs);
            delegate.format(converted);
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
                throws IOException {
            if (!active) {
                return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart);
            }
            ensureCapacity(pendingLength + length);
            final int bytesRead = input.read(pending, pendingLength, length);
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) {
                    return C.RESULT_END_OF_INPUT;
                }
                throw new EOFException();
            }
            pendingLength += bytesRead;
            if (sampleDataPart != SAMPLE_DATA_PART_MAIN) {
                pendingHasOtherParts = true;
            }
            return bytesRead;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            if (!active) {
                delegate.sampleData(data, length, sampleDataPart);
                return;
            }
            ensureCapacity(pendingLength + length);
            data.readBytes(pending, pendingLength, length);
            pendingLength += length;
            if (sampleDataPart != SAMPLE_DATA_PART_MAIN) {
                pendingHasOtherParts = true;
            }
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset, @Nullable CryptoData cryptoData) {
            if (!active) {
                delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData);
                return;
            }
            final int sampleEnd = pendingLength - offset;
            final int sampleStart = sampleEnd - size;
            if (sampleStart < 0 || sampleEnd < 0) {
                // Inconsistent with what was written: should never happen, keep the data flowing.
                Log.w(TAG, "Unexpected sample layout, size=" + size + " offset=" + offset + " pending=" + pendingLength);
                flushPendingUnchanged();
                delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData);
                return;
            }

            final int outputSize;
            if (cryptoData != null) {
                // Encrypted samples are not converted.
                outputSize = size;
                forward(pending, sampleStart, size, SAMPLE_DATA_PART_MAIN);
            } else if ((flags & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA) != 0) {
                // Matroska tracks with block additions (e.g. any track with a Dolby Vision
                // BlockAdditionMapping) are written as:
                // sample size (4 bytes) || sample data || supplemental data
                outputSize = convertSampleWithSupplementalData(sampleStart, size);
            } else if (pendingHasOtherParts) {
                // Unknown layout: leave it untouched.
                outputSize = size;
                forward(pending, sampleStart, size, SAMPLE_DATA_PART_MAIN);
            } else {
                outputSize = converter.convert(pending, sampleStart, size);
                forward(converter.getOutput(), 0, outputSize, SAMPLE_DATA_PART_MAIN);
            }
            delegate.sampleMetadata(timeUs, flags, outputSize, 0, cryptoData);

            // Bytes after the sample belong to the next one.
            final int remaining = pendingLength - sampleEnd;
            if (remaining > 0) {
                System.arraycopy(pending, sampleEnd, pending, 0, remaining);
            }
            pendingLength = remaining;
            pendingHasOtherParts = false;
        }

        private int convertSampleWithSupplementalData(int sampleStart, int size) {
            final int mainSize = size >= 4 ? readInt(pending, sampleStart) : -1;
            if (mainSize < 0 || mainSize > size - 4) {
                Log.w(TAG, "Unexpected supplemental data layout, size=" + size + " mainSize=" + mainSize);
                forward(pending, sampleStart, size, SAMPLE_DATA_PART_MAIN);
                return size;
            }
            final int supplementalStart = sampleStart + 4 + mainSize;
            final int supplementalSize = size - 4 - mainSize;
            final int convertedSize = converter.convert(pending, sampleStart + 4, mainSize);
            sizePrefix[0] = (byte) (convertedSize >>> 24);
            sizePrefix[1] = (byte) (convertedSize >>> 16);
            sizePrefix[2] = (byte) (convertedSize >>> 8);
            sizePrefix[3] = (byte) convertedSize;
            forward(sizePrefix, 0, 4, SAMPLE_DATA_PART_SUPPLEMENTAL);
            forward(converter.getOutput(), 0, convertedSize, SAMPLE_DATA_PART_MAIN);
            forward(pending, supplementalStart, supplementalSize, SAMPLE_DATA_PART_SUPPLEMENTAL);
            return 4 + convertedSize + supplementalSize;
        }

        private static int readInt(byte[] data, int offset) {
            return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                    | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
        }

        private void forward(byte[] data, int offset, int length, int sampleDataPart) {
            if (length <= 0) {
                return;
            }
            // The sample queue copies the bytes right away, so the buffers can be reused afterwards.
            scratch.reset(data, offset + length);
            scratch.setPosition(offset);
            delegate.sampleData(scratch, length, sampleDataPart);
        }

        private void flushPendingUnchanged() {
            forward(pending, 0, pendingLength, SAMPLE_DATA_PART_MAIN);
            pendingLength = 0;
            pendingHasOtherParts = false;
        }

        private void ensureCapacity(int capacity) {
            if (capacity <= pending.length) {
                return;
            }
            int newSize = pending.length * 2;
            while (newSize < capacity) {
                newSize *= 2;
            }
            final byte[] grown = new byte[newSize];
            System.arraycopy(pending, 0, grown, 0, pendingLength);
            pending = grown;
        }

        void logStats() {
            if (converter.getSampleCount() == 0) {
                return;
            }
            Log.i(TAG, "samples=" + converter.getSampleCount()
                    + " rpuConverted=" + converter.getConvertedRpuCount()
                    + " rpuFailed=" + converter.getFailedRpuCount()
                    + " elNalDropped=" + converter.getDroppedElNalUnitCount()
                    + " elType=" + converter.getLastElType()
                    + (converter.getLastError() != null ? " lastError=" + converter.getLastError() : ""));
        }
    }
}
