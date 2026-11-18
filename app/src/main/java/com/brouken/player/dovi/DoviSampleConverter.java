package com.brouken.player.dovi;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts HEVC access units of a single-track Dolby Vision stream (Annex B, as produced by the
 * Media3 extractors).
 *
 * <p>{@link Mode#PROFILE_8_1} (profile 7 source):
 * <ul>
 *   <li>enhancement layer NAL units (UNSPEC63, or nuh_layer_id &gt; 0) are dropped,</li>
 *   <li>the RPU NAL unit (UNSPEC62) is converted to profile 8.1,</li>
 *   <li>every other NAL unit (base layer) is copied unchanged.</li>
 * </ul>
 *
 * <p>{@link Mode#HDR10_PLUS} (profile 7 or 8 source), for devices without Dolby Vision:
 * <ul>
 *   <li>enhancement layer and RPU NAL units are dropped, leaving a plain HDR10 HEVC stream,</li>
 *   <li>a prefix SEI with HDR10+ metadata derived from the RPU (L1, or L6 as fallback) is
 *       inserted before the first slice, unless the access unit already carries HDR10+.</li>
 * </ul>
 * Not thread safe: use one instance per track (the counters can be read from other threads).
 */
public final class DoviSampleConverter {

    public enum Mode { PROFILE_8_1, HDR10_PLUS }

    public static final int NAL_UNSPEC62 = 62;
    public static final int NAL_UNSPEC63 = 63;

    private static final byte[] START_CODE = {0, 0, 0, 1};

    private final Mode mode;

    private byte[] output = new byte[256 * 1024];
    private int outputLength;

    // HDR10+ mode
    private int[] nalStarts = new int[64];
    private int[] nalEnds = new int[64];
    private int[] lastLevel1;
    private int[] lastLevel6;
    private volatile long insertedHdr10PlusSei;
    private volatile long existingHdr10PlusSei;
    private volatile Hdr10PlusMetadata lastHdr10Plus;
    private volatile int lastProfile;

    public DoviSampleConverter() {
        this(Mode.PROFILE_8_1);
    }

    public DoviSampleConverter(Mode mode) {
        this.mode = mode;
    }

    public Mode getMode() {
        return mode;
    }

    private volatile long samples;
    private volatile long convertedRpus;
    private volatile long failedRpus;
    private volatile long droppedElNalUnits;
    private volatile DoviRpu.ElType lastElType = DoviRpu.ElType.NONE;
    private volatile String lastError;

    /**
     * Converts one access unit.
     *
     * @return the length of the converted access unit, available through {@link #getOutput()}
     */
    public int convert(byte[] data, int offset, int length) {
        samples++;
        outputLength = 0;
        if (mode == Mode.HDR10_PLUS) {
            return convertToHdr10Plus(data, offset, length);
        }
        final int end = offset + length;

        int prefix = findStartCodePrefix(data, offset, end);
        if (prefix == end) {
            // Not Annex B: leave untouched.
            append(data, offset, length);
            return outputLength;
        }

        boolean rpuWritten = false;
        while (prefix < end) {
            final int nalStart = prefix + 3;
            final int nextPrefix = findStartCodePrefix(data, nalStart, end);
            int nalEnd = nextPrefix;
            if (nextPrefix < end) {
                // Zero bytes before a start code belong to the 4-byte start code / trailing_zero_8bits.
                while (nalEnd > nalStart && data[nalEnd - 1] == 0) {
                    nalEnd--;
                }
            }
            final int nalLength = nalEnd - nalStart;
            if (nalLength > 0) {
                final int nalType = nalLength >= 2 ? (data[nalStart] >> 1) & 0x3F : -1;
                if (nalType == NAL_UNSPEC63 || (nalLength >= 2 && layerId(data, nalStart) != 0)) {
                    // Enhancement layer, wrapped (UNSPEC63) or not (nuh_layer_id > 0).
                    droppedElNalUnits++;
                } else if (nalType == NAL_UNSPEC62) {
                    if (!rpuWritten) {
                        rpuWritten = writeConvertedRpu(data, nalStart, nalLength);
                    }
                    // Further RPUs in the same access unit are dropped, like dovi_tool does.
                } else {
                    append(START_CODE, 0, 4);
                    append(data, nalStart, nalLength);
                }
            }
            prefix = nextPrefix;
        }
        return outputLength;
    }

    private int convertToHdr10Plus(byte[] data, int offset, int length) {
        final int end = offset + length;
        final int count = splitNalUnits(data, offset, end);
        if (count == 0) {
            append(data, offset, length);
            return outputLength;
        }

        // The RPU is at the end of the access unit: read it before writing anything.
        boolean hasHdr10Plus = false;
        boolean rpuParsed = false;
        for (int i = 0; i < count; i++) {
            final int start = nalStarts[i];
            final int nalLength = nalEnds[i] - start;
            if (nalLength < 2) {
                continue;
            }
            final int nalType = (data[start] >> 1) & 0x3F;
            if (nalType == NAL_UNSPEC62 && !rpuParsed) {
                rpuParsed = true;
                readRpu(data, start, nalLength);
            } else if (nalType == Hdr10PlusMetadata.NAL_SEI_PREFIX
                    && Hdr10PlusMetadata.isHdr10PlusSei(data, start, nalLength)) {
                hasHdr10Plus = true;
            }
        }

        byte[] sei = null;
        if (hasHdr10Plus) {
            existingHdr10PlusSei++;
        } else {
            final Hdr10PlusMetadata metadata;
            if (lastLevel1 != null) {
                metadata = Hdr10PlusGenerator.fromLevel1(lastLevel1[0], lastLevel1[1], lastLevel1[2]);
            } else if (lastLevel6 != null && lastLevel6[2] > 0) {
                metadata = Hdr10PlusGenerator.fromMaxCll(lastLevel6[2], lastLevel6[3]);
            } else {
                metadata = null;
            }
            if (metadata != null) {
                sei = metadata.toSeiNalUnit();
                lastHdr10Plus = metadata;
            }
        }

        for (int i = 0; i < count; i++) {
            final int start = nalStarts[i];
            final int nalLength = nalEnds[i] - start;
            if (nalLength <= 0) {
                continue;
            }
            final int nalType = nalLength >= 2 ? (data[start] >> 1) & 0x3F : -1;
            if (nalType == NAL_UNSPEC63 || (nalLength >= 2 && layerId(data, start) != 0)) {
                droppedElNalUnits++;
                continue;
            }
            if (nalType == NAL_UNSPEC62) {
                continue;
            }
            if (sei != null && nalType >= 0 && nalType < 32) {
                // Prefix SEI before the first VCL NAL unit (slice) of the access unit.
                append(START_CODE, 0, 4);
                append(sei, 0, sei.length);
                insertedHdr10PlusSei++;
                sei = null;
            }
            append(START_CODE, 0, 4);
            append(data, start, nalLength);
        }
        return outputLength;
    }

    private void readRpu(byte[] data, int offset, int length) {
        try {
            final DoviRpu rpu = DoviRpu.parseUnspec62Nalu(data, offset, length);
            lastProfile = rpu.getProfile();
            if (rpu.getElType() != DoviRpu.ElType.NONE) {
                lastElType = rpu.getElType();
            }
            final int[] level1 = rpu.getLevel1();
            if (level1 != null) {
                lastLevel1 = level1;
            }
            final int[] level6 = rpu.getLevel6();
            if (level6 != null) {
                lastLevel6 = level6;
            }
            convertedRpus++;
        } catch (DoviException | RuntimeException e) {
            // Keep the metadata of the previous frame.
            failedRpus++;
            lastError = e.getMessage();
        }
    }

    /** Splits an Annex B access unit; returns the number of NAL units in nalStarts/nalEnds. */
    private int splitNalUnits(byte[] data, int offset, int end) {
        int count = 0;
        int prefix = findStartCodePrefix(data, offset, end);
        while (prefix < end) {
            final int nalStart = prefix + 3;
            final int nextPrefix = findStartCodePrefix(data, nalStart, end);
            int nalEnd = nextPrefix;
            if (nextPrefix < end) {
                while (nalEnd > nalStart && data[nalEnd - 1] == 0) {
                    nalEnd--;
                }
            }
            if (count == nalStarts.length) {
                nalStarts = java.util.Arrays.copyOf(nalStarts, count * 2);
                nalEnds = java.util.Arrays.copyOf(nalEnds, count * 2);
            }
            nalStarts[count] = nalStart;
            nalEnds[count] = nalEnd;
            count++;
            prefix = nextPrefix;
        }
        return count;
    }

    public long getInsertedHdr10PlusSeiCount() {
        return insertedHdr10PlusSei;
    }

    public long getExistingHdr10PlusSeiCount() {
        return existingHdr10PlusSei;
    }

    /** The last generated HDR10+ metadata, or null. */
    public Hdr10PlusMetadata getLastHdr10Plus() {
        return lastHdr10Plus;
    }

    /** Dolby Vision profile of the last RPU (HDR10+ mode). */
    public int getLastProfile() {
        return lastProfile;
    }

    /** HEVC nuh_layer_id of the NAL unit starting at {@code offset}. */
    static int layerId(byte[] data, int offset) {
        return ((data[offset] & 0x01) << 5) | ((data[offset + 1] & 0xFF) >> 3);
    }

    private boolean writeConvertedRpu(byte[] data, int offset, int length) {
        try {
            final DoviRpu rpu = DoviRpu.parseUnspec62Nalu(data, offset, length);
            final DoviRpu.ElType elType = rpu.getElType();
            if (rpu.getProfile() == 7) {
                rpu.convertToProfile81();
            } else if (rpu.getProfile() != 8) {
                throw new DoviException("Unexpected RPU profile " + rpu.getProfile());
            }
            final byte[] converted = rpu.writeHevcUnspec62Nalu();
            append(START_CODE, 0, 4);
            append(converted, 0, converted.length);
            convertedRpus++;
            if (elType != DoviRpu.ElType.NONE) {
                // RPUs reusing the previous mapping (use_prev_vdr_rpu_flag) don't carry the EL type.
                lastElType = elType;
            }
            return true;
        } catch (DoviException | RuntimeException e) {
            // A profile 7 RPU must not reach a profile 8 decoder: drop it, the decoder keeps going
            // with the base layer (HDR10) until the next valid RPU.
            failedRpus++;
            lastError = e.getMessage();
            return false;
        }
    }

    public byte[] getOutput() {
        return output;
    }

    public long getSampleCount() {
        return samples;
    }

    public long getConvertedRpuCount() {
        return convertedRpus;
    }

    public long getFailedRpuCount() {
        return failedRpus;
    }

    public long getDroppedElNalUnitCount() {
        return droppedElNalUnits;
    }

    public DoviRpu.ElType getLastElType() {
        return lastElType;
    }

    public String getLastError() {
        return lastError;
    }

    private void append(byte[] src, int offset, int length) {
        if (outputLength + length > output.length) {
            int newSize = output.length * 2;
            while (newSize < outputLength + length) {
                newSize *= 2;
            }
            final byte[] grown = new byte[newSize];
            System.arraycopy(output, 0, grown, 0, outputLength);
            output = grown;
        }
        System.arraycopy(src, offset, output, outputLength, length);
        outputLength += length;
    }

    /** Returns the index of the next 0x000001 sequence at or after {@code from}, or {@code end}. */
    static int findStartCodePrefix(byte[] data, int from, int end) {
        for (int i = from; i + 2 < end; i++) {
            if ((data[i + 2] & 0xFF) > 1) {
                // Fast skip: data[i + 2] can't be part of a start code at i, i + 1 or i + 2.
                i += 2;
            } else if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                return i;
            }
        }
        return end;
    }

    // ---------------------------------------------------------------------------------------------
    // Format helpers

    /** Returns the profile 8 codecs string for a profile 7 one (e.g. dvhe.07.06 -> dvhe.08.06). */
    public static String convertCodecsString(String codecs) {
        if (codecs != null && codecs.startsWith("dvhe.07")) {
            return "dvhe.08" + codecs.substring("dvhe.07".length());
        }
        return codecs;
    }

    public static boolean isProfile7Codecs(String codecs) {
        return codecs != null && codecs.startsWith("dvhe.07");
    }

    /** Profile 7 or 8 (HEVC, HDR10 compatible base layer unless HLG): convertible to HDR10+. */
    public static boolean isProfile7Or8Codecs(String codecs) {
        return codecs != null && (codecs.startsWith("dvhe.07") || codecs.startsWith("dvhe.08"));
    }

    /**
     * Removes UNSPEC62/UNSPEC63 NAL units (Dolby Vision RPU / enhancement layer) and NAL units of
     * other layers from Annex B codec initialization data, keeping the base layer VPS/SPS/PPS/SEI.
     */
    public static List<byte[]> filterInitializationData(List<byte[]> initializationData) {
        final List<byte[]> result = new ArrayList<>(initializationData.size());
        for (byte[] csd : initializationData) {
            result.add(filterInitializationData(csd));
        }
        return result;
    }

    static byte[] filterInitializationData(byte[] csd) {
        final int first = findStartCodePrefix(csd, 0, csd.length);
        if (first == csd.length) {
            return csd;
        }
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(csd.length);
        // Keep anything before the first start code untouched (should be nothing).
        out.write(csd, 0, first > 0 && csd[first - 1] == 0 ? first - 1 : first);
        int prefix = first;
        boolean removed = false;
        while (prefix < csd.length) {
            final int nalStart = prefix + 3;
            final int nextPrefix = findStartCodePrefix(csd, nalStart, csd.length);
            int nalEnd = nextPrefix;
            if (nextPrefix < csd.length) {
                while (nalEnd > nalStart && csd[nalEnd - 1] == 0) {
                    nalEnd--;
                }
            }
            if (nalEnd > nalStart) {
                final int nalType = (csd[nalStart] >> 1) & 0x3F;
                final boolean enhancementLayer = nalEnd - nalStart >= 2 && layerId(csd, nalStart) != 0;
                if (nalType == NAL_UNSPEC62 || nalType == NAL_UNSPEC63 || enhancementLayer) {
                    removed = true;
                } else {
                    out.write(START_CODE, 0, 4);
                    out.write(csd, nalStart, nalEnd - nalStart);
                }
            }
            prefix = nextPrefix;
        }
        return removed ? out.toByteArray() : csd;
    }
}
