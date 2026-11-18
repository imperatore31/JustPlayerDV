package com.brouken.player.dovi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * HDR10+ metadata tests. The reference SEI NAL units in app/src/test/resources/hdr10plus come from
 * hdr10plus_tool's test assets (MIT License, see LICENSE-hdr10plus_tool.txt):
 * conformance_sei.bin holds the HDR10+ SEIs of the "ToS" conformance streams, hdr10plus_tool_sei.bin
 * the SEIs written by hdr10plus_tool itself (regular_start_code_4_injected.hevc).
 */
public class Hdr10PlusTest {

    private static List<byte[]> readNalUnits(String name) throws IOException {
        final byte[] data;
        try (InputStream in = Hdr10PlusTest.class.getResourceAsStream("/hdr10plus/" + name)) {
            assertNotNull("Missing test asset " + name, in);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            data = out.toByteArray();
        }
        final List<byte[]> nals = new ArrayList<>();
        int start = -1;
        for (int i = 0; i + 4 <= data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                if (start >= 0) {
                    nals.add(Arrays.copyOfRange(data, start, i));
                }
                start = i + 4;
                i += 3;
            }
        }
        nals.add(Arrays.copyOfRange(data, start, data.length));
        return nals;
    }

    /** Returns the user_data_registered_itu_t_t35 payload of a single-message HDR10+ SEI. */
    private static byte[] payloadOf(byte[] nal) {
        final byte[] rbsp = DoviRpu.clearEmulationPrevention(nal, 2, nal.length - 2);
        assertEquals(Hdr10PlusMetadata.SEI_USER_DATA_REGISTERED_ITU_T_T35, rbsp[0]);
        final int size = rbsp[1] & 0xFF;
        return Arrays.copyOfRange(rbsp, 2, 2 + size);
    }

    @Test
    public void conformancePayloadsRoundTrip() throws Exception {
        final List<byte[]> nals = readNalUnits("conformance_sei.bin");
        assertEquals(68, nals.size());
        for (byte[] nal : nals) {
            assertTrue(Hdr10PlusMetadata.isHdr10PlusSei(nal, 0, nal.length));
            final byte[] payload = payloadOf(nal);
            final Hdr10PlusMetadata metadata = Hdr10PlusMetadata.parse(payload, 0, payload.length);
            assertArrayEquals(payload, metadata.encode());
        }
    }

    @Test
    public void seiNalUnitsMatchHdr10PlusTool() throws Exception {
        final List<byte[]> nals = readNalUnits("hdr10plus_tool_sei.bin");
        assertEquals(3, nals.size());
        for (byte[] nal : nals) {
            final byte[] payload = payloadOf(nal);
            final Hdr10PlusMetadata metadata = Hdr10PlusMetadata.parse(payload, 0, payload.length);
            metadata.validate();
            assertArrayEquals(nal, metadata.toSeiNalUnit());
            assertArrayEquals(payload, Hdr10PlusMetadata.parseSei(nal, 0, nal.length).encode());
        }
    }

    @Test
    public void fieldsMatchHdr10PlusToolJson() throws Exception {
        // First scene of hdr10plus_tool's regular_metadata.json
        final byte[] payload = payloadOf(readNalUnits("hdr10plus_tool_sei.bin").get(0));
        final Hdr10PlusMetadata m = Hdr10PlusMetadata.parse(payload, 0, payload.length);
        assertArrayEquals(new long[]{17830, 16895, 14252}, m.maxscl);
        assertEquals(1037, m.averageMaxrgb);
        assertArrayEquals(Hdr10PlusMetadata.DISTRIBUTION_INDEXES_9, m.distributionPercentages);
        assertArrayEquals(new long[]{3, 14024, 43, 56, 219, 1036, 2714, 4668, 14445}, m.distributionValues);
        assertEquals(0, m.targetedSystemDisplayMaximumLuminance);
        assertFalse(m.toneMappingFlag);
    }

    @Test
    public void otherSeiIsNotHdr10Plus() {
        // Prefix SEI with a mastering display colour volume message (payloadType 137)
        final byte[] sei = {0x4E, 0x01, (byte) 0x89, 0x02, 0x11, 0x22, (byte) 0x80};
        assertFalse(Hdr10PlusMetadata.isHdr10PlusSei(sei, 0, sei.length));
        final byte[] slice = {0x26, 0x01, (byte) 0xAF};
        assertFalse(Hdr10PlusMetadata.isHdr10PlusSei(slice, 0, slice.length));
    }

    @Test
    public void generatedMetadataIsValidProfileA() throws Exception {
        for (int max = 0; max <= 4095; max += 117) {
            for (int avg = 0; avg <= max; avg += 211) {
                for (int min = 0; min <= avg; min += 401) {
                    final Hdr10PlusMetadata m = Hdr10PlusGenerator.fromLevel1(min, max, avg);
                    m.validate();
                    assertFalse(m.toneMappingFlag);
                    assertEquals(0, m.targetedSystemDisplayMaximumLuminance);
                    // maxRGB percentiles (all slots but the 5% and 10% ones) never decrease
                    final long[] v = m.distributionValues;
                    final long[] maxRgbSlots = {v[0], v[3], v[4], v[5], v[6], v[7], v[8]};
                    for (int i = 1; i < maxRgbSlots.length; i++) {
                        assertTrue(maxRgbSlots[i] >= maxRgbSlots[i - 1]);
                    }
                    assertTrue(v[8] <= m.getMaxScl());
                    assertTrue(v[2] >= 0 && v[2] <= 100);
                    // The SEI must round trip
                    final byte[] payload = m.encode();
                    assertArrayEquals(payload, Hdr10PlusMetadata.parse(payload, 0, payload.length).encode());
                }
            }
        }
    }

    @Test
    public void inverseOfDoviToolMapping() {
        // dovi_tool generate (HDR10+ -> L1, peak source MaxScl): max_pq = PQ(max MaxScl / 10),
        // avg_pq = PQ(average_rgb / 10). Converting back must give the same L1 values, within the
        // 0.1 cd/m² resolution of HDR10+ (which matters only for very dark values).
        for (int max = 500; max <= 4095; max += 37) {
            for (int avg = 300; avg <= max; avg += 53) {
                final Hdr10PlusMetadata m = Hdr10PlusGenerator.fromLevel1(0, max, avg);
                assertEquals(Pq.code12ToNits(max), m.getMaxScl() / 10.0, 0.05 + 1e-9);
                assertEquals(Pq.code12ToNits(avg), m.getAverageMaxrgb() / 10.0, 0.05 + 1e-9);
                if (Pq.code12ToNits(avg) >= 20) {
                    assertEquals(max, Pq.nitsToCode12(m.getMaxScl() / 10.0), 1);
                    assertEquals(avg, Pq.nitsToCode12(m.getAverageMaxrgb() / 10.0), 1);
                }
            }
        }
    }

    @Test
    public void brighterScenesHaveFewerPixelsBelow100Nits() {
        final int max = Pq.nitsToCode12(1000);
        long previous = 101;
        for (int avgNits : new int[]{5, 20, 50, 100, 200, 400}) {
            final Hdr10PlusMetadata m = Hdr10PlusGenerator.fromLevel1(0, max, Pq.nitsToCode12(avgNits));
            final long below100 = m.distributionValues[2];
            assertTrue(avgNits + " nits: " + below100, below100 <= previous);
            previous = below100;
        }
    }

    @Test
    public void dolbyVisionLevel1IsRead() throws Exception {
        for (String name : new String[]{"fel_orig.bin", "mel_orig.bin", "profile8.bin", "trailing_bytes_rpu.bin"}) {
            final DoviRpu rpu = DoviRpuTest.parse(DoviRpuTest.assetAsNal(name));
            final int[] l1 = rpu.getLevel1();
            assertNotNull(name, l1);
            assertTrue(name + " " + Arrays.toString(l1), l1[0] <= l1[2] && l1[2] <= l1[1] && l1[1] <= 4095);
            final Hdr10PlusMetadata m = Hdr10PlusGenerator.fromLevel1(l1[0], l1[1], l1[2]);
            m.validate();
        }
    }
}
