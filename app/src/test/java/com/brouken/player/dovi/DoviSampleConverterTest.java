package com.brouken.player.dovi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DoviSampleConverterTest {

    private static final byte[] SC4 = {0, 0, 0, 1};
    private static final byte[] SC3 = {0, 0, 1};

    // Fake base layer NAL units (HEVC NAL header = type << 1, layer/tid = 0x01).
    private static final byte[] VPS = {0x40, 0x01, 0x0C, 0x01, (byte) 0xFF};
    private static final byte[] SPS = {0x42, 0x01, 0x01, 0x02, 0x00, 0x00, 0x03, 0x00, 0x7F};
    private static final byte[] SLICE = {0x26, 0x01, (byte) 0xAF, 0x00, 0x00, 0x03, 0x01, 0x55};
    private static final byte[] PREFIX_SEI = {0x4E, 0x01, 0x05, 0x10, (byte) 0x80};
    // Enhancement layer NAL unit: UNSPEC63 header 0x7E01 wrapping an EL slice.
    private static final byte[] EL = {0x7E, 0x01, 0x26, 0x01, 0x12, 0x34, 0x00, 0x00, 0x03, 0x00, 0x56};

    private static byte[] concat(byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    @Test
    public void dropsEnhancementLayerAndConvertsRpu() throws Exception {
        final byte[] rpu = DoviRpuTest.assetAsNal("fel_orig.bin");
        final byte[] expectedRpu = DoviRpu.convertUnspec62NaluToProfile81(rpu, 0, rpu.length);

        // Same layout as a MakeMKV single track profile 7 access unit: BL, EL, RPU last.
        final byte[] sample = concat(SC4, VPS, SC4, SPS, SC4, PREFIX_SEI, SC4, SLICE, SC4, EL, SC4, EL, SC4, rpu);

        final DoviSampleConverter converter = new DoviSampleConverter();
        final int length = converter.convert(sample, 0, sample.length);
        final byte[] out = Arrays.copyOf(converter.getOutput(), length);

        assertArrayEquals(concat(SC4, VPS, SC4, SPS, SC4, PREFIX_SEI, SC4, SLICE, SC4, expectedRpu), out);
        assertEquals(1, converter.getConvertedRpuCount());
        assertEquals(0, converter.getFailedRpuCount());
        assertEquals(2, converter.getDroppedElNalUnitCount());
        assertEquals(DoviRpu.ElType.FEL, converter.getLastElType());
    }

    @Test
    public void handlesThreeByteStartCodesAndOffsets() throws Exception {
        final byte[] rpu = DoviRpuTest.assetAsNal("mel_orig.bin");
        final byte[] expectedRpu = DoviRpu.convertUnspec62NaluToProfile81(rpu, 0, rpu.length);
        final byte[] sample = concat(new byte[]{9, 9, 9}, SC3, SLICE, SC3, EL, SC4, rpu, new byte[]{7});

        final DoviSampleConverter converter = new DoviSampleConverter();
        // Exclude the 3 leading garbage bytes and the trailing byte via offset/length.
        final int length = converter.convert(sample, 3, sample.length - 4);
        final byte[] out = Arrays.copyOf(converter.getOutput(), length);

        assertArrayEquals(concat(SC4, SLICE, SC4, expectedRpu), out);
        assertEquals(DoviRpu.ElType.MEL, converter.getLastElType());
    }

    @Test
    public void corruptedRpuIsDropped() throws Exception {
        final byte[] rpu = DoviRpuTest.assetAsNal("fel_orig.bin");
        rpu[30] ^= 0x01;
        final byte[] sample = concat(SC4, SLICE, SC4, EL, SC4, rpu);

        final DoviSampleConverter converter = new DoviSampleConverter();
        final int length = converter.convert(sample, 0, sample.length);

        assertArrayEquals(concat(SC4, SLICE), Arrays.copyOf(converter.getOutput(), length));
        assertEquals(1, converter.getFailedRpuCount());
    }

    @Test
    public void onlyFirstRpuOfAccessUnitIsKept() throws Exception {
        final byte[] rpu = DoviRpuTest.assetAsNal("mel_orig.bin");
        final byte[] expectedRpu = DoviRpu.convertUnspec62NaluToProfile81(rpu, 0, rpu.length);
        final byte[] sample = concat(SC4, SLICE, SC4, rpu, SC4, rpu);

        final DoviSampleConverter converter = new DoviSampleConverter();
        final int length = converter.convert(sample, 0, sample.length);

        assertArrayEquals(concat(SC4, SLICE, SC4, expectedRpu), Arrays.copyOf(converter.getOutput(), length));
    }

    @Test
    public void largeSampleGrowsOutputBuffer() {
        final byte[] big = new byte[1024 * 1024];
        Arrays.fill(big, (byte) 0x55);
        big[0] = 0x26;
        big[1] = 0x01;
        final byte[] sample = concat(SC4, big, SC4, EL);

        final DoviSampleConverter converter = new DoviSampleConverter();
        final int length = converter.convert(sample, 0, sample.length);

        assertArrayEquals(concat(SC4, big), Arrays.copyOf(converter.getOutput(), length));
    }

    @Test
    public void nonAnnexBSampleIsUntouched() {
        final byte[] sample = {1, 2, 3, 4, 5};
        final DoviSampleConverter converter = new DoviSampleConverter();
        final int length = converter.convert(sample, 0, sample.length);
        assertArrayEquals(sample, Arrays.copyOf(converter.getOutput(), length));
    }

    @Test
    public void initializationDataFilter() {
        final byte[] csd = concat(SC4, VPS, SC4, SPS, SC4, EL, SC4, PREFIX_SEI);
        final List<byte[]> filtered =
                DoviSampleConverter.filterInitializationData(Collections.singletonList(csd));
        assertArrayEquals(concat(SC4, VPS, SC4, SPS, SC4, PREFIX_SEI), filtered.get(0));

        final byte[] clean = concat(SC4, VPS, SC4, SPS);
        assertSame(clean, DoviSampleConverter.filterInitializationData(clean));
    }

    @Test
    public void codecsString() {
        assertEquals("dvhe.08.06", DoviSampleConverter.convertCodecsString("dvhe.07.06"));
        assertEquals("dvhe.08.09", DoviSampleConverter.convertCodecsString("dvhe.07.09"));
        assertEquals("dvhe.05.06", DoviSampleConverter.convertCodecsString("dvhe.05.06"));
        assertTrue(DoviSampleConverter.isProfile7Codecs("dvhe.07.06"));
        assertFalse(DoviSampleConverter.isProfile7Codecs("dvhe.08.06"));
        assertFalse(DoviSampleConverter.isProfile7Codecs(null));
    }
}
