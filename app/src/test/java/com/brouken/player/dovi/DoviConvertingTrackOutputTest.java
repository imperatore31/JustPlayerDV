package com.brouken.player.dovi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.TrackOutput;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Tests the Media3 glue with the way the extractors (Matroska, TS) write samples. */
public class DoviConvertingTrackOutputTest {

    private static final byte[] SC = {0, 0, 0, 1};
    private static final byte[] VPS = {0x40, 0x01, 0x0C};
    private static final byte[] EL = {0x7E, 0x01, 0x26, 0x01, 0x05, 0x06, 0x07};

    /** Records the samples like SampleQueue does: all parts in a row, delimited by sampleMetadata. */
    private static final class CapturingTrackOutput implements TrackOutput {
        Format format;
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        final List<byte[]> samples = new ArrayList<>();
        final List<Integer> flags = new ArrayList<>();

        @Override
        public void format(Format format) {
            this.format = format;
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
                throws java.io.IOException {
            final byte[] buffer = new byte[length];
            final int read = input.read(buffer, 0, length);
            if (read > 0) {
                pending.write(buffer, 0, read);
            }
            return read;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            final byte[] buffer = new byte[length];
            data.readBytes(buffer, 0, length);
            pending.write(buffer, 0, length);
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData cryptoData) {
            final byte[] all = pending.toByteArray();
            final int end = all.length - offset;
            samples.add(Arrays.copyOfRange(all, end - size, end));
            this.flags.add(flags);
            pending.reset();
            pending.write(all, end, all.length - end);
        }
    }

    /** DataReader returning the data in small chunks, like a network stream. */
    private static final class ChunkedReader implements DataReader {
        private final byte[] data;
        private int position;

        ChunkedReader(byte[] data) {
            this.data = data;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= data.length) {
                return C.RESULT_END_OF_INPUT;
            }
            final int n = Math.min(length, Math.min(7777, data.length - position));
            System.arraycopy(data, position, buffer, offset, n);
            position += n;
            return n;
        }
    }

    private static byte[] concat(byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static byte[] intBytes(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    private static Format profile7Format() {
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                .setCodecs("dvhe.07.06")
                .setInitializationData(Collections.singletonList(concat(SC, VPS, SC, EL)))
                .build();
    }

    private byte[] slice;
    private byte[] accessUnit;
    private byte[] expected;

    private void createAccessUnit() throws Exception {
        slice = new byte[50000];
        new Random(1).nextBytes(slice);
        slice[0] = 0x26;
        slice[1] = 0x01;
        for (int i = 0; i + 2 < slice.length; i++) {
            if (slice[i] == 0 && slice[i + 1] == 0 && (slice[i + 2] & 0xFF) <= 3) {
                slice[i + 2] = 4;
            }
        }
        final byte[] rpu = DoviRpuTest.assetAsNal("fel_orig.bin");
        final byte[] convertedRpu = DoviRpu.convertUnspec62NaluToProfile81(rpu, 0, rpu.length);
        accessUnit = concat(SC, slice, SC, EL, SC, rpu);
        expected = concat(SC, slice, SC, convertedRpu);
    }

    @Test
    public void formatIsRewritten() {
        final CapturingTrackOutput capture = new CapturingTrackOutput();
        new DoviConvertingExtractorsFactory.ConvertingTrackOutput(capture).format(profile7Format());
        assertEquals("dvhe.08.06", capture.format.codecs);
        assertArrayEquals(concat(SC, VPS), capture.format.initializationData.get(0));
        assertTrue(DoviConvertingExtractorsFactory.isConvertedFormat(capture.format));
    }

    @Test
    public void matroskaSamplesAreConverted() throws Exception {
        createAccessUnit();
        final CapturingTrackOutput capture = new CapturingTrackOutput();
        final TrackOutput output = new DoviConvertingExtractorsFactory.ConvertingTrackOutput(capture);
        output.format(profile7Format());
        for (int i = 0; i < 3; i++) {
            final ChunkedReader reader = new ChunkedReader(accessUnit);
            int left = accessUnit.length;
            while (left > 0) {
                left -= output.sampleData(reader, left, false);
            }
            output.sampleMetadata(i * 41_708L, C.BUFFER_FLAG_KEY_FRAME, accessUnit.length, 0, null);
        }
        assertEquals(3, capture.samples.size());
        for (byte[] sample : capture.samples) {
            assertArrayEquals(expected, sample);
        }
    }

    @Test
    public void matroskaSamplesWithBlockAdditionsAreConverted() throws Exception {
        // Matroska tracks with a BlockAdditionMapping (Dolby Vision) have maxBlockAdditionId > 0:
        // sample size (4 bytes, supplemental part) || sample data || supplemental data.
        createAccessUnit();
        final byte[] blockAdditional = {9, 8, 7, 6, 5};
        final CapturingTrackOutput capture = new CapturingTrackOutput();
        final TrackOutput output = new DoviConvertingExtractorsFactory.ConvertingTrackOutput(capture);
        output.format(profile7Format());
        final int flags = C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA;

        // Without block additional data (the usual case).
        output.sampleData(new ParsableByteArray(intBytes(accessUnit.length)), 4,
                TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL);
        int left = accessUnit.length;
        final ChunkedReader reader = new ChunkedReader(accessUnit);
        while (left > 0) {
            left -= output.sampleData(reader, left, false);
        }
        output.sampleMetadata(0, flags, 4 + accessUnit.length, 0, null);

        // With block additional data.
        output.sampleData(new ParsableByteArray(intBytes(accessUnit.length)), 4,
                TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL);
        output.sampleData(new ParsableByteArray(accessUnit), accessUnit.length);
        output.sampleData(new ParsableByteArray(blockAdditional), blockAdditional.length,
                TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL);
        output.sampleMetadata(1, flags, 4 + accessUnit.length + blockAdditional.length, 0, null);

        assertArrayEquals(concat(intBytes(expected.length), expected), capture.samples.get(0));
        assertArrayEquals(concat(intBytes(expected.length), expected, blockAdditional), capture.samples.get(1));
        assertEquals(Integer.valueOf(flags), capture.flags.get(1));
        final DoviSampleConverter converter = DoviConvertingExtractorsFactory.getLastActiveConverter();
        assertEquals(2, converter.getConvertedRpuCount());
        assertEquals(2, converter.getDroppedElNalUnitCount());
    }

    @Test
    public void tsStyleOffsetsAreHandled() throws Exception {
        createAccessUnit();
        final CapturingTrackOutput capture = new CapturingTrackOutput();
        final TrackOutput output = new DoviConvertingExtractorsFactory.ConvertingTrackOutput(capture);
        output.format(profile7Format());
        final byte[] stream = concat(accessUnit, accessUnit, accessUnit);
        final int split = accessUnit.length + 123;
        output.sampleData(new ParsableByteArray(Arrays.copyOfRange(stream, 0, split)), split);
        output.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, accessUnit.length, 123, null);
        output.sampleData(new ParsableByteArray(Arrays.copyOfRange(stream, split, stream.length)), stream.length - split);
        output.sampleMetadata(1, 0, accessUnit.length, accessUnit.length, null);
        output.sampleMetadata(2, 0, accessUnit.length, 0, null);
        assertEquals(3, capture.samples.size());
        for (byte[] sample : capture.samples) {
            assertArrayEquals(expected, sample);
        }
        assertEquals(0, capture.pending.size());
    }

    @Test
    public void otherTracksArePassedThrough() throws Exception {
        createAccessUnit();
        for (String codecs : new String[]{"dvhe.08.06", "hvc1.2.4.L153"}) {
            final CapturingTrackOutput capture = new CapturingTrackOutput();
            final TrackOutput output = new DoviConvertingExtractorsFactory.ConvertingTrackOutput(capture);
            output.format(new Format.Builder()
                    .setSampleMimeType(codecs.startsWith("dvhe") ? MimeTypes.VIDEO_DOLBY_VISION : MimeTypes.VIDEO_H265)
                    .setCodecs(codecs)
                    .build());
            output.sampleData(new ParsableByteArray(accessUnit), accessUnit.length);
            output.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, accessUnit.length, 0, null);
            assertArrayEquals(accessUnit, capture.samples.get(0));
            assertEquals(codecs, capture.format.codecs);
        }
    }

    @Test
    public void encryptedSamplesArePassedThrough() throws Exception {
        createAccessUnit();
        final CapturingTrackOutput capture = new CapturingTrackOutput();
        final TrackOutput output = new DoviConvertingExtractorsFactory.ConvertingTrackOutput(capture);
        output.format(profile7Format());
        output.sampleData(new ParsableByteArray(accessUnit), accessUnit.length);
        output.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, accessUnit.length, 0,
                new TrackOutput.CryptoData(C.CRYPTO_MODE_AES_CTR, new byte[16], 0, 0));
        assertArrayEquals(accessUnit, capture.samples.get(0));
    }
}
