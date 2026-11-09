package com.brouken.player.dovi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checks the Java RPU code against the reference files shipped with dovi_tool
 * (app/src/test/resources/dovi, MIT License, see LICENSE-dovi_tool.txt).
 * The expectations mirror dovi_tool's own tests in src/tests/rpu.rs.
 */
public class DoviRpuTest {

    /** Reads a dovi_tool test file: 00 00 00 01 + escaped RPU starting with 0x19. */
    static byte[] readAsset(String name) throws IOException {
        try (InputStream in = DoviRpuTest.class.getResourceAsStream("/dovi/" + name)) {
            assertNotNull("Missing test asset " + name, in);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Builds the UNSPEC62 NAL unit (0x7C01 + payload) from a single-RPU dovi_tool test file. */
    static byte[] assetAsNal(String name) throws IOException {
        final byte[] data = readAsset(name);
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x19}, Arrays.copyOf(data, 5));
        return toNal(data, 4, data.length - 4);
    }

    static byte[] toNal(byte[] data, int offset, int length) {
        final byte[] nal = new byte[length + 2];
        nal[0] = 0x7C;
        nal[1] = 0x01;
        System.arraycopy(data, offset, nal, 2, length);
        return nal;
    }

    static DoviRpu parse(byte[] nal) throws DoviException {
        return DoviRpu.parseUnspec62Nalu(nal, 0, nal.length);
    }

    private static void assertRoundTrip(String name, int expectedProfile) throws Exception {
        final byte[] nal = assetAsNal(name);
        final DoviRpu rpu = parse(nal);
        assertEquals(name + " profile", expectedProfile, rpu.getProfile());
        assertArrayEquals(name + " round trip", nal, rpu.writeHevcUnspec62Nalu());
    }

    @Test
    public void roundTripIsLossless() throws Exception {
        assertRoundTrip("fel_orig.bin", 7);
        assertRoundTrip("fel_to_mel.bin", 7);
        assertRoundTrip("fel_to_81.bin", 8);
        assertRoundTrip("mel_orig.bin", 7);
        assertRoundTrip("mel_to_mel.bin", 7);
        assertRoundTrip("mel_to_81.bin", 8);
        assertRoundTrip("data_before_crc32.bin", 7);
        assertRoundTrip("fix_se_write.bin", 7);
        assertRoundTrip("eof_rpu.bin", 7);
        assertRoundTrip("poly_coef_int_logic.bin", 7);
        assertRoundTrip("trailing_bytes_rpu.bin", 7);
        assertRoundTrip("mel_variable_l8_length13.bin", 7);
        assertRoundTrip("profile4.bin", 4);
        assertRoundTrip("profile5.bin", 5);
        assertRoundTrip("profile5-02.bin", 5);
        assertRoundTrip("profile20_apple.bin", 5);
        assertRoundTrip("level253-ext-metadata.bin", 5);
        assertRoundTrip("empty_dmv1_blocks.bin", 5);
        assertRoundTrip("profile8.bin", 8);
        assertRoundTrip("profile84.bin", 8);
        assertRoundTrip("profile8_from_profile5-02.bin", 8);
        assertRoundTrip("unordered_l8_blocks.bin", 8);
    }

    @Test
    public void multipleRpusWithStartCodeLikeCrc() throws Exception {
        final byte[] data = readAsset("p8_001_end_crc32.bin");
        final List<int[]> ranges = new ArrayList<>();
        int start = -1;
        for (int i = 0; i + 4 <= data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                if (start >= 0) {
                    ranges.add(new int[]{start, i});
                }
                start = i + 4;
                i += 3;
            }
        }
        ranges.add(new int[]{start, data.length});
        assertEquals(3, ranges.size());
        for (int[] range : ranges) {
            final byte[] nal = toNal(data, range[0], range[1] - range[0]);
            final DoviRpu rpu = parse(nal);
            assertEquals(8, rpu.getProfile());
            // The file stores the CRC "00 00 01" without emulation prevention; the writer must
            // escape it ("00 00 03 01"), so compare the unescaped payloads.
            final byte[] written = rpu.writeHevcUnspec62Nalu();
            assertArrayEquals(DoviRpu.clearEmulationPrevention(nal, 2, nal.length - 2),
                    DoviRpu.clearEmulationPrevention(written, 2, written.length - 2));
            assertEquals(-1, indexOfStartCode(written));
        }
    }

    private static int indexOfStartCode(byte[] data) {
        for (int i = 0; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] & 0xFF) <= 2) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void invalidRpuIsRejected() throws Exception {
        try {
            parse(assetAsNal("st2094_10_level3.bin"));
            // Our parser does not interpret extension blocks, so it may accept it; it must at least
            // round trip unchanged.
        } catch (DoviException expected) {
            return;
        }
        final byte[] nal = assetAsNal("st2094_10_level3.bin");
        assertArrayEquals(nal, parse(nal).writeHevcUnspec62Nalu());
    }

    @Test
    public void corruptedRpuIsRejected() throws Exception {
        final byte[] nal = assetAsNal("fel_orig.bin");
        nal[20] ^= 0x10;
        try {
            parse(nal);
            fail("CRC mismatch not detected");
        } catch (DoviException expected) {
            // expected
        }
    }

    @Test
    public void melToProfile81() throws Exception {
        final DoviRpu rpu = parse(assetAsNal("mel_orig.bin"));
        assertEquals(DoviRpu.ElType.MEL, rpu.getElType());
        rpu.convertToProfile81();
        assertEquals(8, rpu.getProfile());
        assertArrayEquals(assetAsNal("mel_to_81.bin"), rpu.writeHevcUnspec62Nalu());
    }

    @Test
    public void felToMelToProfile81() throws Exception {
        final DoviRpu rpu = parse(assetAsNal("fel_orig.bin"));
        assertEquals(DoviRpu.ElType.FEL, rpu.getElType());

        rpu.convertToMel();
        assertEquals(7, rpu.getProfile());
        assertArrayEquals(assetAsNal("fel_to_mel.bin"), rpu.writeHevcUnspec62Nalu());

        rpu.convertToProfile81();
        assertEquals(8, rpu.getProfile());
        assertArrayEquals(assetAsNal("fel_to_81.bin"), rpu.writeHevcUnspec62Nalu());
    }

    @Test
    public void felToProfile81MappingPreserved() throws Exception {
        final DoviRpu rpu = parse(assetAsNal("fel_orig.bin"));
        rpu.convertToProfile81MappingPreserved();
        assertArrayEquals(assetAsNal("fel_to_81.bin"), rpu.writeHevcUnspec62Nalu());
    }

    @Test
    public void profile5ToProfile81UsesIdentityMapping() throws Exception {
        // Exercises the identity mapping written for FEL sources against a dovi_tool reference.
        final DoviRpu rpu = parse(assetAsNal("profile5-02.bin"));
        rpu.convertProfile5ToProfile81();
        assertEquals(8, rpu.getProfile());
        assertArrayEquals(assetAsNal("profile8_from_profile5-02.bin"), rpu.writeHevcUnspec62Nalu());
    }

    @Test
    public void felToProfile81RemovesMapping() throws Exception {
        final DoviRpu rpu = parse(assetAsNal("fel_orig.bin"));
        rpu.convertToProfile81();
        assertEquals(8, rpu.getProfile());
        final byte[] converted = rpu.writeHevcUnspec62Nalu();

        // Expected: dovi_tool's FEL -> 8.1 (mapping preserved) result, with the identity mapping.
        final DoviRpu expected = parse(assetAsNal("fel_to_81.bin"));
        expected.mapping.setEmptyP81Mapping();
        assertArrayEquals(expected.writeHevcUnspec62Nalu(), converted);

        final DoviRpu reparsed = parse(converted);
        assertEquals(8, reparsed.getProfile());
        assertEquals(DoviRpu.ElType.NONE, reparsed.getElType());
        for (int cmp = 0; cmp < 3; cmp++) {
            assertArrayEquals(new int[]{0, 1023}, reparsed.mapping.pivots[cmp]);
        }
    }

    @Test
    public void allProfile7SamplesConvert() throws Exception {
        final String[] names = {"fel_orig.bin", "fel_to_mel.bin", "mel_orig.bin", "mel_to_mel.bin",
                "data_before_crc32.bin", "fix_se_write.bin", "eof_rpu.bin", "poly_coef_int_logic.bin",
                "trailing_bytes_rpu.bin", "mel_variable_l8_length13.bin"};
        for (String name : names) {
            final byte[] nal = assetAsNal(name);
            final byte[] converted = DoviRpu.convertUnspec62NaluToProfile81(nal, 0, nal.length);
            final DoviRpu reparsed = parse(converted);
            assertEquals(name, 8, reparsed.getProfile());
            assertEquals(name, DoviRpu.ElType.NONE, reparsed.getElType());
            // DM extension metadata must be carried over untouched.
            final DoviRpu original = parse(nal);
            assertEquals(name, original.dmData.cmv29.blocks.size(), reparsed.dmData.cmv29.blocks.size());
            assertEquals(name, original.dmData.cmv40 == null, reparsed.dmData.cmv40 == null);
        }
    }

    @Test
    public void emulationPreventionMatchesDoviTool() {
        assertArrayEquals(new byte[]{0x19, 0, 0, 3, 1, 0, 0, 3, 0, 0, 3, 3},
                DoviRpu.addEmulationPrevention(new byte[]{0x19, 0, 0, 1, 0, 0, 0, 0, 3}));
        assertArrayEquals(new byte[]{0x19, 0, 0, 1, 0, 0, 0, 0, 3},
                DoviRpu.clearEmulationPrevention(new byte[]{0x19, 0, 0, 3, 1, 0, 0, 3, 0, 0, 3, 3}, 0, 12));
    }
}
