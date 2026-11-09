/*
 * Dolby Vision RPU parser/writer and profile 7 -> 8.1 conversion.
 *
 * This is a Java port of the relevant parts of the "dolby_vision" crate from dovi_tool
 * (https://github.com/quietvoid/dovi_tool), which is distributed under the MIT License:
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
 * Extension metadata blocks (L1..L255) are not interpreted: they are carried over bit for bit,
 * which is what dovi_tool's re-serialization produces for valid blocks.
 */
package com.brouken.player.dovi;

import java.util.ArrayList;
import java.util.List;

public final class DoviRpu {

    public enum ElType { NONE, MEL, FEL }

    private static final int NUM_COMPONENTS = 3;
    private static final int RESHAPING_MAX_PIECES = 8;
    private static final int MMR_MAX_COEFFS = 7;
    private static final int NLQ_NUM_PIVOTS = 2;
    private static final int RPU_PREFIX = 0x19;
    private static final int FINAL_BYTE = 0x80;
    private static final int CRC32_TERMINATOR_BITS = 40;
    private static final int DM_DATA_PAYLOAD2_MIN_BITS = 56;
    private static final int MAX_EXT_BLOCKS = 255;

    // rpu_data_header
    int rpuType;
    int rpuFormat;
    int vdrRpuProfile;
    int vdrRpuLevel;
    boolean vdrSeqInfoPresentFlag;
    boolean chromaResamplingExplicitFilterFlag;
    int coefficientDataType;
    long coefficientLog2Denom;
    int coefficientLog2DenomLength;
    int vdrRpuNormalizedIdc;
    boolean blVideoFullRangeFlag;
    long blBitDepthMinus8;
    long elBitDepthMinus8;
    int extMappingIdc0to4;
    int extMappingIdc5to7;
    long vdrBitDepthMinus8;
    boolean spatialResamplingFilterFlag;
    int reservedZero3Bits;
    boolean elSpatialResamplingFilterFlag;
    boolean disableResidualFlag;
    boolean vdrDmMetadataPresentFlag;
    boolean usePrevVdrRpuFlag;
    long prevVdrRpuId;

    Mapping mapping;
    DmData dmData;
    BitWriter.BitString remaining;
    int trailingZeroes;

    int doviProfile;
    ElType elType = ElType.NONE;

    private DoviRpu() {
    }

    public int getProfile() {
        return doviProfile;
    }

    public ElType getElType() {
        return elType;
    }

    // ---------------------------------------------------------------------------------------------
    // NAL unit level helpers

    /**
     * Converts a HEVC UNSPEC62 NAL unit (starting with the 0x7C01 NAL header) containing a profile 7
     * RPU into a profile 8.1 RPU NAL unit.
     *
     * @return the converted NAL unit, including the 0x7C01 header and emulation prevention bytes
     */
    public static byte[] convertUnspec62NaluToProfile81(byte[] nal, int offset, int length)
            throws DoviException {
        final DoviRpu rpu = parseUnspec62Nalu(nal, offset, length);
        rpu.convertToProfile81();
        return rpu.writeHevcUnspec62Nalu();
    }

    /** Parses a HEVC UNSPEC62 NAL unit, starting with the 0x7C01 NAL header. */
    public static DoviRpu parseUnspec62Nalu(byte[] nal, int offset, int length) throws DoviException {
        if (length < 3 || (nal[offset] & 0xFF) != 0x7C || (nal[offset + 1] & 0xFF) != 0x01) {
            throw new DoviException("Not an UNSPEC62 NAL unit");
        }
        final byte[] unescaped = clearEmulationPrevention(nal, offset + 2, length - 2);
        return parse(unescaped);
    }

    public byte[] writeHevcUnspec62Nalu() throws DoviException {
        final byte[] rpuData = writeRpuData();
        final byte[] escaped = addEmulationPrevention(rpuData);
        final byte[] out = new byte[escaped.length + 2];
        out[0] = 0x7C;
        out[1] = 0x01;
        System.arraycopy(escaped, 0, out, 2, escaped.length);
        return out;
    }

    /** Same algorithm as dovi_tool's clear_start_code_emulation_prevention_3_byte. */
    static byte[] clearEmulationPrevention(byte[] data, int offset, int length) {
        if (length <= 2) {
            final byte[] copy = new byte[length];
            System.arraycopy(data, offset, copy, 0, length);
            return copy;
        }
        final byte[] out = new byte[length];
        int n = 0;
        out[n++] = data[offset];
        out[n++] = data[offset + 1];
        for (int i = 2; i < length; i++) {
            if (!(data[offset + i - 2] == 0 && data[offset + i - 1] == 0 && data[offset + i] == 3)) {
                out[n++] = data[offset + i];
            }
        }
        if (n == length) {
            return out;
        }
        final byte[] trimmed = new byte[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** Same algorithm as dovi_tool's add_start_code_emulation_prevention_3_byte. */
    static byte[] addEmulationPrevention(byte[] data) {
        final byte[] out = new byte[data.length + data.length / 2 + 4];
        int n = 0;
        for (int i = 0; i < data.length; i++) {
            final int b = data[i] & 0xFF;
            if (n > 2 && out[n - 2] == 0 && out[n - 1] == 0 && b <= 3) {
                out[n++] = 3;
            }
            out[n++] = (byte) b;
        }
        final byte[] trimmed = new byte[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    // ---------------------------------------------------------------------------------------------
    // Parsing

    /** Parses unescaped RPU data starting with the 0x19 prefix byte. */
    static DoviRpu parse(byte[] data) throws DoviException {
        if (data.length < 1 || (data[0] & 0xFF) != RPU_PREFIX) {
            throw new DoviException("Invalid RPU prefix");
        }
        int trailingZeroes = 0;
        for (int i = data.length - 1; i >= 0 && data[i] == 0; i--) {
            trailingZeroes++;
        }
        final int rpuEnd = data.length - trailingZeroes;
        if (rpuEnd < 7) {
            throw new DoviException("RPU data too short");
        }
        if ((data[rpuEnd - 1] & 0xFF) != FINAL_BYTE) {
            throw new DoviException("Invalid RPU last byte");
        }
        final int crc32Start = rpuEnd - 5;
        final int computedCrc = Crc32Mpeg2.compute(data, 1, crc32Start - 1);
        final int receivedCrc = ((data[crc32Start] & 0xFF) << 24) | ((data[crc32Start + 1] & 0xFF) << 16)
                | ((data[crc32Start + 2] & 0xFF) << 8) | (data[crc32Start + 3] & 0xFF);
        if (computedCrc != receivedCrc) {
            throw new DoviException("RPU CRC32 mismatch");
        }

        final BitReader reader = new BitReader(data, 0, rpuEnd);
        final DoviRpu rpu = new DoviRpu();
        reader.readBits(8); // prefix
        rpu.parseHeader(reader);
        rpu.doviProfile = rpu.computeProfile();
        rpu.validateHeader(rpu.doviProfile);

        if (!rpu.usePrevVdrRpuFlag) {
            rpu.mapping = Mapping.parse(reader, rpu);
            rpu.elType = rpu.mapping.nlq != null ? rpu.mapping.nlq.elType() : ElType.NONE;
        }
        if (rpu.vdrDmMetadataPresentFlag) {
            rpu.dmData = DmData.parse(reader, rpu.reservedZero3Bits == 1);
        }

        while (!reader.isByteAligned()) {
            if (reader.readBit()) {
                throw new DoviException("rpu_alignment_zero_bit != 0");
            }
        }
        if (reader.bitsLeft() > CRC32_TERMINATOR_BITS) {
            rpu.remaining = BitWriter.BitString.read(reader, reader.bitsLeft() - CRC32_TERMINATOR_BITS);
        }
        if (reader.bitsLeft() != CRC32_TERMINATOR_BITS) {
            throw new DoviException("Unexpected RPU length");
        }
        final int parsedCrc = (int) reader.readBits(32);
        if (parsedCrc != computedCrc) {
            throw new DoviException("Parsed RPU CRC32 mismatch");
        }
        if (reader.readBits(8) != FINAL_BYTE) {
            throw new DoviException("Invalid RPU final byte");
        }
        rpu.trailingZeroes = trailingZeroes;
        rpu.validate();
        return rpu;
    }

    private void parseHeader(BitReader reader) throws DoviException {
        rpuType = reader.readInt(6);
        if (rpuType != 2) {
            throw new DoviException("Unsupported rpu_type " + rpuType);
        }
        rpuFormat = reader.readInt(11);
        vdrRpuProfile = reader.readInt(4);
        vdrRpuLevel = reader.readInt(4);
        vdrSeqInfoPresentFlag = reader.readBit();

        if (vdrSeqInfoPresentFlag) {
            chromaResamplingExplicitFilterFlag = reader.readBit();
            coefficientDataType = reader.readInt(2);
            if (coefficientDataType == 0) {
                coefficientLog2Denom = reader.readUe();
            }
            vdrRpuNormalizedIdc = reader.readInt(2);
            blVideoFullRangeFlag = reader.readBit();

            if ((rpuFormat & 0x700) == 0) {
                blBitDepthMinus8 = reader.readUe();
                final long elBitDepthAndExt = reader.readUe();
                elBitDepthMinus8 = elBitDepthAndExt & 0xFF;
                final int extMappingIdc = (int) ((elBitDepthAndExt >> 8) & 0xFF);
                extMappingIdc0to4 = extMappingIdc & 0x1F;
                extMappingIdc5to7 = extMappingIdc >> 5;
                vdrBitDepthMinus8 = reader.readUe();
                spatialResamplingFilterFlag = reader.readBit();
                reservedZero3Bits = reader.readInt(3);
                elSpatialResamplingFilterFlag = reader.readBit();
                disableResidualFlag = reader.readBit();
            }

            if (coefficientDataType == 0) {
                coefficientLog2DenomLength = (int) coefficientLog2Denom;
            } else if (coefficientDataType == 1) {
                coefficientLog2DenomLength = 32;
            } else {
                throw new DoviException("Invalid coefficient_data_type " + coefficientDataType);
            }
        }

        vdrDmMetadataPresentFlag = reader.readBit();
        usePrevVdrRpuFlag = reader.readBit();
        if (usePrevVdrRpuFlag) {
            prevVdrRpuId = reader.readUe();
        }
    }

    private int computeProfile() {
        if (vdrRpuProfile == 0) {
            return blVideoFullRangeFlag ? 5 : 0;
        } else if (vdrRpuProfile == 1) {
            if (elSpatialResamplingFilterFlag && !disableResidualFlag) {
                return vdrBitDepthMinus8 == 4 ? 7 : 4;
            }
            return 8;
        }
        return 0;
    }

    private void validateHeader(int profile) throws DoviException {
        if (profile == 5) {
            check(vdrRpuProfile == 0, "profile 5: vdr_rpu_profile should be 0");
            check(blVideoFullRangeFlag, "profile 5: bl_video_full_range_flag should be true");
        } else if (profile == 7 || profile == 8) {
            check(vdrRpuProfile == 1, "vdr_rpu_profile should be 1");
        }
        check(vdrRpuLevel == 0, "vdr_rpu_level should be 0");
        check(blBitDepthMinus8 == 2, "bl_bit_depth_minus8 should be 2");
        check(elBitDepthMinus8 == 2, "el_bit_depth_minus8 should be 2");
        check(vdrBitDepthMinus8 <= 6, "vdr_bit_depth_minus8 should be <= 6");
        check(coefficientLog2Denom <= 23, "coefficient_log2_denom should be <= 23");
    }

    private void validate() throws DoviException {
        validateHeader(doviProfile);
        if (mapping != null) {
            mapping.validate(doviProfile);
        }
        if (dmData != null) {
            dmData.validate();
        }
    }

    private static void check(boolean condition, String message) throws DoviException {
        if (!condition) {
            throw new DoviException(message);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Conversion

    /**
     * Converts a profile 7 (or 8) RPU to profile 8.1, like dovi_tool's mode 2: the NLQ (enhancement
     * layer) data is removed and, for FEL sources, the luma/chroma mapping is replaced by an
     * identity mapping. MEL sources keep their mapping, which makes the conversion lossless.
     */
    public void convertToProfile81() throws DoviException {
        if (doviProfile != 7 && doviProfile != 8) {
            throw new DoviException("Cannot convert profile " + doviProfile + " to 8.1");
        }
        final ElType sourceElType = elType;
        convertToP81();
        if (sourceElType == ElType.FEL) {
            removeMapping();
        }
        updateProfile();
    }

    /** Old dovi_tool mode 2: profile 8.1 keeping the mapping curves, even for FEL sources. */
    void convertToProfile81MappingPreserved() throws DoviException {
        if (doviProfile != 7 && doviProfile != 8) {
            throw new DoviException("Cannot convert profile " + doviProfile + " to 8.1");
        }
        convertToP81();
        updateProfile();
    }

    /** dovi_tool mode 1. */
    void convertToMel() throws DoviException {
        if (doviProfile != 7) {
            throw new DoviException("Only profile 7 can be converted to MEL");
        }
        elSpatialResamplingFilterFlag = true;
        disableResidualFlag = false;
        if (mapping != null) {
            mapping.nlqMethodIdc = 0;
            mapping.hasNlqMethodIdc = true;
            mapping.nlqPredPivotValue = new int[]{0, 1023};
            if (mapping.nlq == null) {
                throw new DoviException("Missing NLQ data");
            }
            mapping.nlq.convertToMel();
        }
        updateProfile();
    }

    /** Profile 5 to 8.1, used to validate the identity mapping against dovi_tool's test vectors. */
    void convertProfile5ToProfile81() throws DoviException {
        if (doviProfile != 5) {
            throw new DoviException("RPU is not profile 5");
        }
        convertToP81();
        vdrRpuProfile = 1;
        blVideoFullRangeFlag = false;
        removeMapping();
        updateProfile();
    }

    private void convertToP81() {
        elSpatialResamplingFilterFlag = false;
        disableResidualFlag = true;
        if (mapping != null) {
            mapping.hasNlqMethodIdc = false;
            mapping.nlqPredPivotValue = null;
            mapping.numXPartitionsMinus1 = 0;
            mapping.numYPartitionsMinus1 = 0;
            mapping.nlq = null;
        }
        if (dmData != null) {
            dmData.setP81Coefficients();
        }
    }

    private void removeMapping() {
        if (mapping != null) {
            mapping.setEmptyP81Mapping();
        }
    }

    private void updateProfile() {
        doviProfile = computeProfile();
        elType = mapping != null && mapping.nlq != null ? mapping.nlq.elType() : ElType.NONE;
    }

    // ---------------------------------------------------------------------------------------------
    // Writing

    /** Writes the unescaped RPU data, starting with the 0x19 prefix. */
    byte[] writeRpuData() throws DoviException {
        validate();
        final BitWriter writer = new BitWriter(512);
        writer.writeBits(8, RPU_PREFIX);
        writeHeader(writer);
        if (!usePrevVdrRpuFlag && mapping != null) {
            mapping.write(writer, this);
        }
        if (vdrDmMetadataPresentFlag && dmData != null) {
            dmData.write(writer);
        }
        if (remaining != null) {
            writer.writeBitString(remaining);
        }
        writer.byteAlign();
        final int length = (int) (writer.bitLength() >>> 3);
        final byte[] body = writer.bytes(1, length);
        final int crc = Crc32Mpeg2.compute(body, 0, body.length);
        writer.writeBits(32, crc & 0xFFFFFFFFL);
        writer.writeBits(8, FINAL_BYTE);
        for (int i = 0; i < trailingZeroes; i++) {
            writer.writeBits(8, 0);
        }
        return writer.toByteArray();
    }

    private void writeHeader(BitWriter writer) {
        writer.writeBits(6, rpuType);
        writer.writeBits(11, rpuFormat);
        writer.writeBits(4, vdrRpuProfile);
        writer.writeBits(4, vdrRpuLevel);
        writer.writeBit(vdrSeqInfoPresentFlag);

        if (vdrSeqInfoPresentFlag) {
            writer.writeBit(chromaResamplingExplicitFilterFlag);
            writer.writeBits(2, coefficientDataType);
            if (coefficientDataType == 0) {
                writer.writeUe(coefficientLog2Denom);
            }
            writer.writeBits(2, vdrRpuNormalizedIdc);
            writer.writeBit(blVideoFullRangeFlag);

            if ((rpuFormat & 0x700) == 0) {
                writer.writeUe(blBitDepthMinus8);
                final long extMappingIdc = ((long) extMappingIdc5to7 << 5) | extMappingIdc0to4;
                writer.writeUe((extMappingIdc << 8) | elBitDepthMinus8);
                writer.writeUe(vdrBitDepthMinus8);
                writer.writeBit(spatialResamplingFilterFlag);
                writer.writeBits(3, reservedZero3Bits);
                writer.writeBit(elSpatialResamplingFilterFlag);
                writer.writeBit(disableResidualFlag);
            }
        }

        writer.writeBit(vdrDmMetadataPresentFlag);
        writer.writeBit(usePrevVdrRpuFlag);
        if (usePrevVdrRpuFlag) {
            writer.writeUe(prevVdrRpuId);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // rpu_data_mapping

    static final class Mapping {
        long vdrRpuId;
        long mappingColorSpace;
        long mappingChromaFormatIdc;
        final long[] numPivotsMinus2 = new long[NUM_COMPONENTS];
        final int[][] pivots = new int[NUM_COMPONENTS][];

        boolean hasNlqMethodIdc;
        int nlqMethodIdc;
        int[] nlqPredPivotValue;

        long numXPartitionsMinus1;
        long numYPartitionsMinus1;

        final Piece[][] pieces = new Piece[NUM_COMPONENTS][];
        Nlq nlq;

        static Mapping parse(BitReader reader, DoviRpu header) throws DoviException {
            final Mapping m = new Mapping();
            m.vdrRpuId = reader.readUe();
            m.mappingColorSpace = reader.readUe();
            m.mappingChromaFormatIdc = reader.readUe();

            final int blBitDepth = (int) (header.blBitDepthMinus8 + 8);

            for (int cmp = 0; cmp < NUM_COMPONENTS; cmp++) {
                m.numPivotsMinus2[cmp] = reader.readUe();
                check(m.numPivotsMinus2[cmp] < RESHAPING_MAX_PIECES, "num_pivots_minus2 out of range");
                final int numPivots = (int) m.numPivotsMinus2[cmp] + 2;
                m.pivots[cmp] = new int[numPivots];
                for (int i = 0; i < numPivots; i++) {
                    m.pivots[cmp][i] = (int) reader.readBits(blBitDepth);
                }
            }

            // Profile 7 only
            if ((header.rpuFormat & 0x700) == 0 && !header.disableResidualFlag) {
                m.nlqMethodIdc = reader.readInt(3);
                check(m.nlqMethodIdc == 0, "Unsupported NLQ method");
                m.hasNlqMethodIdc = true;
                m.nlqPredPivotValue = new int[NLQ_NUM_PIVOTS];
                for (int i = 0; i < NLQ_NUM_PIVOTS; i++) {
                    m.nlqPredPivotValue[i] = (int) reader.readBits(blBitDepth);
                }
            }

            m.numXPartitionsMinus1 = reader.readUe();
            m.numYPartitionsMinus1 = reader.readUe();

            for (int cmp = 0; cmp < NUM_COMPONENTS; cmp++) {
                final int numPieces = (int) m.numPivotsMinus2[cmp] + 1;
                m.pieces[cmp] = new Piece[numPieces];
                for (int i = 0; i < numPieces; i++) {
                    m.pieces[cmp][i] = Piece.parse(reader, header);
                }
            }

            if (m.hasNlqMethodIdc) {
                m.nlq = Nlq.parse(reader, header);
            }
            return m;
        }

        void write(BitWriter writer, DoviRpu header) throws DoviException {
            final int blBitDepth = (int) (header.blBitDepthMinus8 + 8);
            writer.writeUe(vdrRpuId);
            writer.writeUe(mappingColorSpace);
            writer.writeUe(mappingChromaFormatIdc);

            for (int cmp = 0; cmp < NUM_COMPONENTS; cmp++) {
                writer.writeUe(numPivotsMinus2[cmp]);
                for (int pivot : pivots[cmp]) {
                    writer.writeBits(blBitDepth, pivot);
                }
            }

            if ((header.rpuFormat & 0x700) == 0 && !header.disableResidualFlag) {
                if (hasNlqMethodIdc) {
                    writer.writeBits(3, nlqMethodIdc);
                }
                if (nlqPredPivotValue != null) {
                    for (int value : nlqPredPivotValue) {
                        writer.writeBits(blBitDepth, value);
                    }
                }
            }

            writer.writeUe(numXPartitionsMinus1);
            writer.writeUe(numYPartitionsMinus1);

            for (int cmp = 0; cmp < NUM_COMPONENTS; cmp++) {
                for (Piece piece : pieces[cmp]) {
                    piece.write(writer, header);
                }
            }

            if (nlq != null) {
                nlq.write(writer, header, hasNlqMethodIdc && nlqMethodIdc == 0);
            }
        }

        void validate(int profile) throws DoviException {
            for (int cmp = 0; cmp < NUM_COMPONENTS; cmp++) {
                check(numPivotsMinus2[cmp] < RESHAPING_MAX_PIECES, "num_pivots_minus2 out of range");
                check(pivots[cmp].length == numPivotsMinus2[cmp] + 2, "Invalid pivot count");
                check(pieces[cmp].length == numPivotsMinus2[cmp] + 1, "Invalid piece count");
            }
            if (profile == 7) {
                check(nlqPredPivotValue != null, "profile 7: nlq_pred_pivot_value should be defined");
                int sum = 0;
                for (int value : nlqPredPivotValue) {
                    sum += value;
                }
                check(sum == 1023, "profile 7: nlq_pred_pivot_value should add up to 1023");
            } else if (profile == 5 || profile == 8) {
                check(!hasNlqMethodIdc, "nlq_method_idc should be undefined");
                check(nlqPredPivotValue == null, "nlq_pred_pivot_value should be undefined");
            }
            check(mappingColorSpace == 0, "mapping_color_space should be 0");
            check(mappingChromaFormatIdc == 0, "mapping_chroma_format_idc should be 0");
        }

        void setEmptyP81Mapping() {
            for (int cmp = 0; cmp < NUM_COMPONENTS; cmp++) {
                numPivotsMinus2[cmp] = 0;
                pivots[cmp] = new int[]{0, 1023};
                final Piece piece = new Piece();
                piece.mappingIdc = Piece.MAPPING_POLYNOMIAL;
                piece.polyOrderMinus1 = 0;
                piece.linearInterpFlag = false;
                piece.polyCoefInt = new long[]{0, 1};
                piece.polyCoef = new long[]{0, 0};
                pieces[cmp] = new Piece[]{piece};
            }
        }
    }

    static final class Piece {
        static final int MAPPING_POLYNOMIAL = 0;
        static final int MAPPING_MMR = 1;

        int mappingIdc;

        long polyOrderMinus1;
        boolean linearInterpFlag;
        long[] polyCoefInt;
        long[] polyCoef;

        int mmrOrderMinus1;
        long mmrConstantInt;
        long mmrConstant;
        long[][] mmrCoefInt;
        long[][] mmrCoef;

        static Piece parse(BitReader reader, DoviRpu header) throws DoviException {
            final Piece p = new Piece();
            final long mappingIdc = reader.readUe();
            final int coefLength = header.coefficientLog2DenomLength;
            final boolean withInt = header.coefficientDataType == 0;

            if (mappingIdc == MAPPING_POLYNOMIAL) {
                p.mappingIdc = MAPPING_POLYNOMIAL;
                p.polyOrderMinus1 = reader.readUe();
                check(p.polyOrderMinus1 <= 1, "poly_order_minus1 out of range");
                p.linearInterpFlag = p.polyOrderMinus1 == 0 && reader.readBit();
                if (p.linearInterpFlag) {
                    throw new DoviException("Polynomial linear interpolation is not supported");
                }
                final int count = (int) p.polyOrderMinus1 + 2;
                p.polyCoefInt = new long[count];
                p.polyCoef = new long[count];
                for (int j = 0; j < count; j++) {
                    if (withInt) {
                        p.polyCoefInt[j] = reader.readSe();
                    }
                    p.polyCoef[j] = reader.readBits(coefLength);
                }
            } else if (mappingIdc == MAPPING_MMR) {
                p.mappingIdc = MAPPING_MMR;
                p.mmrOrderMinus1 = reader.readInt(2);
                check(p.mmrOrderMinus1 <= 2, "mmr_order_minus1 out of range");
                if (withInt) {
                    p.mmrConstantInt = reader.readSe();
                }
                p.mmrConstant = reader.readBits(coefLength);
                final int orders = p.mmrOrderMinus1 + 1;
                p.mmrCoefInt = new long[orders][MMR_MAX_COEFFS];
                p.mmrCoef = new long[orders][MMR_MAX_COEFFS];
                for (int j = 0; j < orders; j++) {
                    for (int k = 0; k < MMR_MAX_COEFFS; k++) {
                        if (withInt) {
                            p.mmrCoefInt[j][k] = reader.readSe();
                        }
                        p.mmrCoef[j][k] = reader.readBits(coefLength);
                    }
                }
            } else {
                throw new DoviException("Invalid mapping_idc " + mappingIdc);
            }
            return p;
        }

        void write(BitWriter writer, DoviRpu header) {
            final int coefLength = header.coefficientLog2DenomLength;
            final boolean withInt = header.coefficientDataType == 0;
            writer.writeUe(mappingIdc);
            if (mappingIdc == MAPPING_POLYNOMIAL) {
                writer.writeUe(polyOrderMinus1);
                if (polyOrderMinus1 == 0) {
                    writer.writeBit(linearInterpFlag);
                }
                for (int j = 0; j < polyCoef.length; j++) {
                    if (withInt) {
                        writer.writeSe(polyCoefInt[j]);
                    }
                    writer.writeBits(coefLength, polyCoef[j]);
                }
            } else {
                writer.writeBits(2, mmrOrderMinus1);
                if (withInt) {
                    writer.writeSe(mmrConstantInt);
                }
                writer.writeBits(coefLength, mmrConstant);
                for (int j = 0; j < mmrCoef.length; j++) {
                    for (int k = 0; k < MMR_MAX_COEFFS; k++) {
                        if (withInt) {
                            writer.writeSe(mmrCoefInt[j][k]);
                        }
                        writer.writeBits(coefLength, mmrCoef[j][k]);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // rpu_data_nlq

    static final class Nlq {
        final int[] nlqOffset = new int[NUM_COMPONENTS];
        final long[] vdrInMaxInt = new long[NUM_COMPONENTS];
        final long[] vdrInMax = new long[NUM_COMPONENTS];
        final long[] linearDeadzoneSlopeInt = new long[NUM_COMPONENTS];
        final long[] linearDeadzoneSlope = new long[NUM_COMPONENTS];
        final long[] linearDeadzoneThresholdInt = new long[NUM_COMPONENTS];
        final long[] linearDeadzoneThreshold = new long[NUM_COMPONENTS];

        static Nlq parse(BitReader reader, DoviRpu header) throws DoviException {
            final Nlq nlq = new Nlq();
            final int coefLength = header.coefficientLog2DenomLength;
            final boolean withInt = header.coefficientDataType == 0;
            final int elBitDepth = (int) (header.elBitDepthMinus8 + 8);
            for (int cmp = 0; cmp < NUM_COMPONENTS; cmp++) {
                nlq.nlqOffset[cmp] = (int) reader.readBits(elBitDepth);
                if (withInt) {
                    nlq.vdrInMaxInt[cmp] = reader.readUe();
                }
                nlq.vdrInMax[cmp] = reader.readBits(coefLength);
                // NLQ_LINEAR_DZ (the only method accepted by Mapping.parse)
                if (withInt) {
                    nlq.linearDeadzoneSlopeInt[cmp] = reader.readUe();
                }
                nlq.linearDeadzoneSlope[cmp] = reader.readBits(coefLength);
                if (withInt) {
                    nlq.linearDeadzoneThresholdInt[cmp] = reader.readUe();
                }
                nlq.linearDeadzoneThreshold[cmp] = reader.readBits(coefLength);
            }
            return nlq;
        }

        void write(BitWriter writer, DoviRpu header, boolean linearDeadzone) {
            final int coefLength = header.coefficientLog2DenomLength;
            final boolean withInt = header.coefficientDataType == 0;
            final int elBitDepth = (int) (header.elBitDepthMinus8 + 8);
            for (int cmp = 0; cmp < NUM_COMPONENTS; cmp++) {
                writer.writeBits(elBitDepth, nlqOffset[cmp]);
                if (withInt) {
                    writer.writeUe(vdrInMaxInt[cmp]);
                }
                writer.writeBits(coefLength, vdrInMax[cmp]);
                if (linearDeadzone) {
                    if (withInt) {
                        writer.writeUe(linearDeadzoneSlopeInt[cmp]);
                    }
                    writer.writeBits(coefLength, linearDeadzoneSlope[cmp]);
                    if (withInt) {
                        writer.writeUe(linearDeadzoneThresholdInt[cmp]);
                    }
                    writer.writeBits(coefLength, linearDeadzoneThreshold[cmp]);
                }
            }
        }

        void convertToMel() {
            for (int cmp = 0; cmp < NUM_COMPONENTS; cmp++) {
                nlqOffset[cmp] = 0;
                vdrInMaxInt[cmp] = 1;
                vdrInMax[cmp] = 0;
                linearDeadzoneSlopeInt[cmp] = 0;
                linearDeadzoneSlope[cmp] = 0;
                linearDeadzoneThresholdInt[cmp] = 0;
                linearDeadzoneThreshold[cmp] = 0;
            }
        }

        ElType elType() {
            for (int cmp = 0; cmp < NUM_COMPONENTS; cmp++) {
                if (nlqOffset[cmp] != 0 || vdrInMaxInt[cmp] != 1 || vdrInMax[cmp] != 0
                        || linearDeadzoneSlopeInt[cmp] != 0 || linearDeadzoneSlope[cmp] != 0
                        || linearDeadzoneThresholdInt[cmp] != 0 || linearDeadzoneThreshold[cmp] != 0) {
                    return ElType.FEL;
                }
            }
            return ElType.MEL;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // vdr_dm_data_payload

    static final class DmData {
        boolean compressed;
        long affectedDmMetadataId;
        long currentDmMetadataId;
        long sceneRefreshFlag;

        final int[] yccToRgbCoef = new int[9];
        final long[] yccToRgbOffset = new long[3];
        final int[] rgbToLmsCoef = new int[9];
        int signalEotf;
        int signalEotfParam0;
        int signalEotfParam1;
        long signalEotfParam2;
        int signalBitDepth;
        int signalColorSpace;
        int signalChromaFormat;
        int signalFullRangeFlag;
        int sourceMinPq;
        int sourceMaxPq;
        int sourceDiagonal;

        ExtMetadata cmv29;
        ExtMetadata cmv40;

        static DmData parse(BitReader reader, boolean compressed) throws DoviException {
            final DmData dm = new DmData();
            dm.compressed = compressed;
            dm.affectedDmMetadataId = reader.readUe();
            dm.currentDmMetadataId = reader.readUe();
            dm.sceneRefreshFlag = reader.readUe();
            if (!compressed) {
                for (int i = 0; i < 9; i++) {
                    dm.yccToRgbCoef[i] = reader.readInt(16);
                }
                for (int i = 0; i < 3; i++) {
                    dm.yccToRgbOffset[i] = reader.readBits(32);
                }
                for (int i = 0; i < 9; i++) {
                    dm.rgbToLmsCoef[i] = reader.readInt(16);
                }
                dm.signalEotf = reader.readInt(16);
                dm.signalEotfParam0 = reader.readInt(16);
                dm.signalEotfParam1 = reader.readInt(16);
                dm.signalEotfParam2 = reader.readBits(32);
                dm.signalBitDepth = reader.readInt(5);
                dm.signalColorSpace = reader.readInt(2);
                dm.signalChromaFormat = reader.readInt(2);
                dm.signalFullRangeFlag = reader.readInt(2);
                dm.sourceMinPq = reader.readInt(12);
                dm.sourceMaxPq = reader.readInt(12);
                dm.sourceDiagonal = reader.readInt(10);
            }
            dm.cmv29 = ExtMetadata.parse(reader);
            if (reader.bitsLeft() >= DM_DATA_PAYLOAD2_MIN_BITS) {
                dm.cmv40 = ExtMetadata.parse(reader);
            }
            return dm;
        }

        void validate() throws DoviException {
            check(affectedDmMetadataId <= 15, "affected_dm_metadata_id should be <= 15");
            if (!compressed) {
                check(signalBitDepth >= 8 && signalBitDepth <= 16, "signal_bit_depth out of range");
                if (signalEotfParam0 == 0 && signalEotfParam1 == 0 && signalEotfParam2 == 0) {
                    check(signalEotf == 65535, "signal_eotf should be 65535");
                }
            }
        }

        void write(BitWriter writer) {
            writer.writeUe(affectedDmMetadataId);
            writer.writeUe(currentDmMetadataId);
            writer.writeUe(sceneRefreshFlag);
            if (!compressed) {
                for (int i = 0; i < 9; i++) {
                    writer.writeBits(16, yccToRgbCoef[i]);
                }
                for (int i = 0; i < 3; i++) {
                    writer.writeBits(32, yccToRgbOffset[i]);
                }
                for (int i = 0; i < 9; i++) {
                    writer.writeBits(16, rgbToLmsCoef[i]);
                }
                writer.writeBits(16, signalEotf);
                writer.writeBits(16, signalEotfParam0);
                writer.writeBits(16, signalEotfParam1);
                writer.writeBits(32, signalEotfParam2);
                writer.writeBits(5, signalBitDepth);
                writer.writeBits(2, signalColorSpace);
                writer.writeBits(2, signalChromaFormat);
                writer.writeBits(2, signalFullRangeFlag);
                writer.writeBits(12, sourceMinPq);
                writer.writeBits(12, sourceMaxPq);
                writer.writeBits(10, sourceDiagonal);
            }
            if (cmv29 != null) {
                cmv29.write(writer);
            }
            if (cmv40 != null) {
                cmv40.write(writer);
            }
        }

        void setP81Coefficients() {
            final int[] ycc = {9574, 0, 13802, 9574, -1540, -5348, 9574, 17610, 0};
            for (int i = 0; i < 9; i++) {
                yccToRgbCoef[i] = ycc[i] & 0xFFFF;
            }
            yccToRgbOffset[0] = 16777216L;
            yccToRgbOffset[1] = 134217728L;
            yccToRgbOffset[2] = 134217728L;
            final int[] lms = {7222, 8771, 390, 2654, 12430, 1300, 0, 422, 15962};
            System.arraycopy(lms, 0, rgbToLmsCoef, 0, 9);
            signalColorSpace = 0;
        }
    }

    /** A list of extension metadata blocks (CM v2.9 or CM v4.0), carried verbatim. */
    static final class ExtMetadata {
        final List<ExtBlock> blocks = new ArrayList<>();

        static ExtMetadata parse(BitReader reader) throws DoviException {
            final long numExtBlocks = reader.readUe();
            check(numExtBlocks <= MAX_EXT_BLOCKS, "Too many extension metadata blocks");
            while (!reader.isByteAligned()) {
                if (reader.readBit()) {
                    throw new DoviException("dm_alignment_zero_bit != 0");
                }
            }
            final ExtMetadata meta = new ExtMetadata();
            for (long i = 0; i < numExtBlocks; i++) {
                final ExtBlock block = new ExtBlock();
                block.length = reader.readUe();
                check(block.length > 0 && block.length <= 1024, "Invalid extension block length");
                block.level = reader.readInt(8);
                block.payload = BitWriter.BitString.read(reader, block.length * 8);
                meta.blocks.add(block);
            }
            return meta;
        }

        void write(BitWriter writer) {
            writer.writeUe(blocks.size());
            writer.byteAlign();
            for (ExtBlock block : blocks) {
                writer.writeUe(block.length);
                writer.writeBits(8, block.level);
                writer.writeBitString(block.payload);
            }
        }
    }

    static final class ExtBlock {
        long length;
        int level;
        BitWriter.BitString payload;
    }
}
