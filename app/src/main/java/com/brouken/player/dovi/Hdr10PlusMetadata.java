/*
 * HDR10+ (SMPTE ST 2094-40, application 4) dynamic metadata parser/writer.
 *
 * Java port of parts of the "hdr10plus" crate from hdr10plus_tool
 * (https://github.com/quietvoid/hdr10plus_tool), which is distributed under the MIT License:
 *
 *   MIT License
 *
 *   Copyright (c) quietvoid
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in all
 *   copies or substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *   SOFTWARE.
 *
 * Only one processing window is supported (the only case allowed by application version 1).
 */
package com.brouken.player.dovi;

import java.util.Arrays;

public final class Hdr10PlusMetadata {

    public static final int COUNTRY_CODE = 0xB5;
    public static final int TERMINAL_PROVIDER_CODE = 0x003C;
    public static final int TERMINAL_PROVIDER_ORIENTED_CODE = 0x0001;
    public static final int APPLICATION_IDENTIFIER = 4;

    static final int NAL_SEI_PREFIX = 39;
    static final int SEI_USER_DATA_REGISTERED_ITU_T_T35 = 4;

    static final int[] DISTRIBUTION_INDEXES_9 = {1, 5, 10, 25, 50, 75, 90, 95, 99};
    static final int[] DISTRIBUTION_INDEXES_10 = {1, 5, 10, 25, 50, 75, 90, 95, 98, 99};

    int countryCode = COUNTRY_CODE;
    int terminalProviderCode = TERMINAL_PROVIDER_CODE;
    int terminalProviderOrientedCode = TERMINAL_PROVIDER_ORIENTED_CODE;
    int applicationIdentifier = APPLICATION_IDENTIFIER;
    int applicationVersion = 1;
    int numWindows = 1;

    long targetedSystemDisplayMaximumLuminance;
    boolean targetedSystemDisplayActualPeakLuminanceFlag;
    int[][] targetedSystemDisplayActualPeakLuminance;

    /** Per component max, in units of 0.1 cd/m² (0 to 100000). */
    final long[] maxscl = new long[3];
    /** Average maxRGB, in units of 0.1 cd/m². */
    long averageMaxrgb;
    int[] distributionPercentages = new int[0];
    long[] distributionValues = new long[0];
    int fractionBrightPixels;

    boolean masteringDisplayActualPeakLuminanceFlag;
    int[][] masteringDisplayActualPeakLuminance;

    boolean toneMappingFlag;
    int kneePointX;
    int kneePointY;
    int[] bezierCurveAnchors = new int[0];

    boolean colorSaturationMappingFlag;
    int colorSaturationWeight;

    public long getMaxScl() {
        return Math.max(maxscl[0], Math.max(maxscl[1], maxscl[2]));
    }

    public long getAverageMaxrgb() {
        return averageMaxrgb;
    }

    // ---------------------------------------------------------------------------------------------
    // Parsing / writing of the user_data_registered_itu_t_t35 payload

    /** Parses a payload starting with itu_t_t35_country_code. */
    static Hdr10PlusMetadata parse(byte[] data, int offset, int length) throws DoviException {
        final BitReader reader = new BitReader(data, offset, length);
        final Hdr10PlusMetadata m = new Hdr10PlusMetadata();
        m.countryCode = reader.readInt(8);
        m.terminalProviderCode = reader.readInt(16);
        m.terminalProviderOrientedCode = reader.readInt(16);
        m.applicationIdentifier = reader.readInt(8);
        m.applicationVersion = reader.readInt(8);
        m.numWindows = reader.readInt(2);
        if (m.numWindows != 1) {
            throw new DoviException("Unsupported HDR10+ num_windows " + m.numWindows);
        }

        m.targetedSystemDisplayMaximumLuminance = reader.readBits(27);
        m.targetedSystemDisplayActualPeakLuminanceFlag = reader.readBit();
        if (m.targetedSystemDisplayActualPeakLuminanceFlag) {
            m.targetedSystemDisplayActualPeakLuminance = readPeakLuminanceTable(reader);
        }

        for (int i = 0; i < 3; i++) {
            m.maxscl[i] = reader.readBits(17);
        }
        m.averageMaxrgb = reader.readBits(17);
        final int count = reader.readInt(4);
        m.distributionPercentages = new int[count];
        m.distributionValues = new long[count];
        for (int i = 0; i < count; i++) {
            m.distributionPercentages[i] = reader.readInt(7);
            m.distributionValues[i] = reader.readBits(17);
        }
        m.fractionBrightPixels = reader.readInt(10);

        m.masteringDisplayActualPeakLuminanceFlag = reader.readBit();
        if (m.masteringDisplayActualPeakLuminanceFlag) {
            m.masteringDisplayActualPeakLuminance = readPeakLuminanceTable(reader);
        }

        m.toneMappingFlag = reader.readBit();
        if (m.toneMappingFlag) {
            m.kneePointX = reader.readInt(12);
            m.kneePointY = reader.readInt(12);
            final int anchors = reader.readInt(4);
            m.bezierCurveAnchors = new int[anchors];
            for (int i = 0; i < anchors; i++) {
                m.bezierCurveAnchors[i] = reader.readInt(10);
            }
        }

        m.colorSaturationMappingFlag = reader.readBit();
        if (m.colorSaturationMappingFlag) {
            m.colorSaturationWeight = reader.readInt(6);
        }
        return m;
    }

    private static int[][] readPeakLuminanceTable(BitReader reader) throws DoviException {
        final int rows = reader.readInt(5);
        final int cols = reader.readInt(5);
        final int[][] table = new int[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                table[i][j] = reader.readInt(4);
            }
        }
        return table;
    }

    private static void writePeakLuminanceTable(BitWriter writer, int[][] table) {
        final int rows = table.length;
        final int cols = rows > 0 ? table[0].length : 0;
        writer.writeBits(5, rows);
        writer.writeBits(5, cols);
        for (int[] row : table) {
            for (int value : row) {
                writer.writeBits(4, value);
            }
        }
    }

    /** Writes the payload, starting with itu_t_t35_country_code, byte aligned. */
    byte[] encode() {
        final BitWriter writer = new BitWriter(64);
        writer.writeBits(8, countryCode);
        writer.writeBits(16, terminalProviderCode);
        writer.writeBits(16, terminalProviderOrientedCode);
        writer.writeBits(8, applicationIdentifier);
        writer.writeBits(8, applicationVersion);
        writer.writeBits(2, numWindows);

        writer.writeBits(27, targetedSystemDisplayMaximumLuminance);
        writer.writeBit(targetedSystemDisplayActualPeakLuminanceFlag);
        if (targetedSystemDisplayActualPeakLuminanceFlag) {
            writePeakLuminanceTable(writer, targetedSystemDisplayActualPeakLuminance);
        }

        for (int i = 0; i < 3; i++) {
            writer.writeBits(17, maxscl[i]);
        }
        writer.writeBits(17, averageMaxrgb);
        writer.writeBits(4, distributionPercentages.length);
        for (int i = 0; i < distributionPercentages.length; i++) {
            writer.writeBits(7, distributionPercentages[i]);
            writer.writeBits(17, distributionValues[i]);
        }
        writer.writeBits(10, fractionBrightPixels);

        writer.writeBit(masteringDisplayActualPeakLuminanceFlag);
        if (masteringDisplayActualPeakLuminanceFlag) {
            writePeakLuminanceTable(writer, masteringDisplayActualPeakLuminance);
        }

        writer.writeBit(toneMappingFlag);
        if (toneMappingFlag) {
            writer.writeBits(12, kneePointX);
            writer.writeBits(12, kneePointY);
            writer.writeBits(4, bezierCurveAnchors.length);
            for (int anchor : bezierCurveAnchors) {
                writer.writeBits(10, anchor);
            }
        }

        writer.writeBit(colorSaturationMappingFlag);
        if (colorSaturationMappingFlag) {
            writer.writeBits(6, colorSaturationWeight);
        }
        writer.byteAlign();
        return writer.toByteArray();
    }

    /** Same checks as hdr10plus_tool's validation (application version 1, profile A or B). */
    void validate() throws DoviException {
        check(applicationIdentifier == APPLICATION_IDENTIFIER, "Invalid application_identifier");
        check(applicationVersion == 1, "Invalid application_version");
        check(numWindows == 1, "Invalid num_windows");
        check(!targetedSystemDisplayActualPeakLuminanceFlag, "Invalid targeted_system_display_actual_peak_luminance_flag");
        check(!masteringDisplayActualPeakLuminanceFlag, "Invalid mastering_display_actual_peak_luminance_flag");
        check(!colorSaturationMappingFlag, "Invalid color_saturation_mapping_flag");
        check(targetedSystemDisplayMaximumLuminance <= 10000, "Invalid targeted_system_display_maximum_luminance");
        if (toneMappingFlag) {
            check(targetedSystemDisplayMaximumLuminance != 0, "Profile B needs a target display");
        } else {
            check(targetedSystemDisplayMaximumLuminance == 0, "Profile A needs a zero target display");
        }
        for (long v : maxscl) {
            check(v <= 100_000, "Invalid MaxScl");
        }
        check(averageMaxrgb <= 100_000, "Invalid average_maxrgb");
        final int[] expected = distributionPercentages.length == 9 ? DISTRIBUTION_INDEXES_9
                : distributionPercentages.length == 10 ? DISTRIBUTION_INDEXES_10 : null;
        check(expected != null && Arrays.equals(expected, distributionPercentages), "Invalid distribution indexes");
        for (long v : distributionValues) {
            check(v <= 100_000, "Invalid distribution value");
        }
        if (toneMappingFlag) {
            check(kneePointX <= 4095 && kneePointY <= 4095, "Invalid knee point");
            check(bezierCurveAnchors.length <= 9, "Too many Bezier curve anchors");
            for (int anchor : bezierCurveAnchors) {
                check(anchor < 1024, "Invalid Bezier curve anchor");
            }
        }
    }

    private static void check(boolean condition, String message) throws DoviException {
        if (!condition) {
            throw new DoviException(message);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // HEVC SEI NAL units

    /**
     * Builds a HEVC prefix SEI NAL unit (without start code) with a single
     * user_data_registered_itu_t_t35 message, like hdr10plus_tool's encode_hdr10plus_nal.
     */
    byte[] toSeiNalUnit() {
        final byte[] payload = encode();
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(payload.length + 8);
        out.write(NAL_SEI_PREFIX << 1); // forbidden_zero_bit, nal_unit_type, nuh_layer_id (msb)
        out.write(0x01); // nuh_layer_id (lsb) = 0, nuh_temporal_id_plus1 = 1
        out.write(SEI_USER_DATA_REGISTERED_ITU_T_T35);
        int size = payload.length;
        while (size >= 255) {
            out.write(0xFF);
            size -= 255;
        }
        out.write(size);
        out.write(payload, 0, payload.length);
        out.write(0x80); // rbsp_trailing_bits
        return DoviRpu.addEmulationPrevention(out.toByteArray());
    }

    /**
     * Returns whether the NAL unit (without start code) is a prefix SEI carrying HDR10+ metadata.
     */
    static boolean isHdr10PlusSei(byte[] data, int offset, int length) {
        if (length < 3 || ((data[offset] >> 1) & 0x3F) != NAL_SEI_PREFIX) {
            return false;
        }
        final byte[] rbsp = DoviRpu.clearEmulationPrevention(data, offset + 2, length - 2);
        int position = 0;
        while (position < rbsp.length && (rbsp[position] & 0xFF) != 0x80) {
            int type = 0;
            while (position < rbsp.length && (rbsp[position] & 0xFF) == 0xFF) {
                type += 255;
                position++;
            }
            if (position >= rbsp.length) {
                return false;
            }
            type += rbsp[position++] & 0xFF;
            int size = 0;
            while (position < rbsp.length && (rbsp[position] & 0xFF) == 0xFF) {
                size += 255;
                position++;
            }
            if (position >= rbsp.length) {
                return false;
            }
            size += rbsp[position++] & 0xFF;
            if (type == SEI_USER_DATA_REGISTERED_ITU_T_T35 && size >= 7 && position + 7 <= rbsp.length
                    && (rbsp[position] & 0xFF) == COUNTRY_CODE
                    && (rbsp[position + 1] & 0xFF) == 0x00 && (rbsp[position + 2] & 0xFF) == TERMINAL_PROVIDER_CODE
                    && (rbsp[position + 3] & 0xFF) == 0x00 && (rbsp[position + 4] & 0xFF) == TERMINAL_PROVIDER_ORIENTED_CODE
                    && (rbsp[position + 5] & 0xFF) == APPLICATION_IDENTIFIER) {
                return true;
            }
            position += size;
        }
        return false;
    }
}
