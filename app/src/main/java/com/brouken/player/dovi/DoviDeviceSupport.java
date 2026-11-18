package com.brouken.player.dovi;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.view.Display;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Reports which Dolby Vision HEVC profiles the device decoders support. */
public final class DoviDeviceSupport {

    private static final String MIME_DOLBY_VISION = "video/dolby-vision";
    private static final String MIME_HEVC = "video/hevc";
    /** MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus (API 29). */
    public static final int HEVC_PROFILE_MAIN10_HDR10_PLUS = 0x2000;
    /** Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS (API 29). */
    private static final int HDR_TYPE_HDR10_PLUS = 4;

    private static volatile DoviDeviceSupport instance;

    public final boolean profile7;
    public final boolean profile8;
    /** Highest {@link MediaCodecInfo.CodecProfileLevel} level declared for profile 8, or 0. */
    public final int profile8MaxLevel;
    /** Dolby Vision decoder name -> short description of its profiles/levels. */
    public final Map<String, String> decoders;
    /** HEVC decoders declaring the HEVC Main10 HDR10+ profile. */
    public final Set<String> hdr10PlusDecoders;

    private DoviDeviceSupport(boolean profile7, boolean profile8, int profile8MaxLevel, Map<String, String> decoders,
                              Set<String> hdr10PlusDecoders) {
        this.profile7 = profile7;
        this.profile8 = profile8;
        this.profile8MaxLevel = profile8MaxLevel;
        this.decoders = decoders;
        this.hdr10PlusDecoders = hdr10PlusDecoders;
    }

    /**
     * True when Dolby Vision can't be played but HDR10+ can, so converting Dolby Vision to HDR10+
     * is worth it (e.g. Samsung and Google Pixel devices).
     */
    public boolean isHdr10PlusConversionUseful(Context context) {
        final boolean dolbyVision = (profile8 || profile7) && isDisplayDolbyVision(context);
        return !dolbyVision && (isDisplayHdr10Plus(context) || !hdr10PlusDecoders.isEmpty());
    }

    /** Whether the default display reports HDR10+ support. */
    public static boolean isDisplayHdr10Plus(Context context) {
        return hasHdrType(context, HDR_TYPE_HDR10_PLUS, 29);
    }

    public static DoviDeviceSupport get() {
        DoviDeviceSupport support = instance;
        if (support == null) {
            support = query();
            instance = support;
        }
        return support;
    }

    /** True when converting profile 7 to 8.1 makes Dolby Vision playback possible. */
    public boolean isConversionUseful() {
        return profile8 && !profile7;
    }

    public boolean isDolbyVisionDecoder(String name) {
        return name != null && decoders.containsKey(name);
    }

    /** Whether the default display reports Dolby Vision support (what Media3 checks too). */
    public static boolean isDisplayDolbyVision(Context context) {
        return hasHdrType(context, Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION, 26);
    }

    private static boolean hasHdrType(Context context, int hdrType, int minSdk) {
        if (Build.VERSION.SDK_INT < minSdk) {
            return false;
        }
        final DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        final Display display = displayManager != null ? displayManager.getDisplay(Display.DEFAULT_DISPLAY) : null;
        if (display == null || !display.isHdr() || display.getHdrCapabilities() == null) {
            return false;
        }
        for (int type : display.getHdrCapabilities().getSupportedHdrTypes()) {
            if (type == hdrType) {
                return true;
            }
        }
        return false;
    }

    /** Converts the level of a codecs string such as "dvhe.08.06" to a CodecProfileLevel level. */
    public static int levelFromCodecs(String codecs) {
        if (codecs == null) {
            return 0;
        }
        final String[] parts = codecs.split("\\.");
        if (parts.length < 3) {
            return 0;
        }
        try {
            final int level = Integer.parseInt(parts[2]);
            return level >= 1 && level <= 13 ? 1 << (level - 1) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Human readable Dolby Vision level, e.g. 0x20 -> "UHD 24". */
    public static String levelName(int level) {
        switch (level) {
            case 0x1: return "HD 24";
            case 0x2: return "HD 30";
            case 0x4: return "FHD 24";
            case 0x8: return "FHD 30";
            case 0x10: return "FHD 60";
            case 0x20: return "UHD 24";
            case 0x40: return "UHD 30";
            case 0x80: return "UHD 48";
            case 0x100: return "UHD 60";
            case 0x200: return "UHD 120";
            case 0x400: return "8K 30";
            case 0x800: return "8K 60";
            default: return level == 0 ? "-" : "0x" + Integer.toHexString(level);
        }
    }

    private static String profileName(int profile) {
        switch (profile) {
            case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtr: return "P4";
            case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn: return "P5";
            case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDth: return "P6";
            case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb: return "P7";
            case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt: return "P8";
            case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavSe: return "P9";
            default: return null;
        }
    }

    private static DoviDeviceSupport query() {
        boolean p7 = false;
        boolean p8 = false;
        int p8MaxLevel = 0;
        final Map<String, String> names = new LinkedHashMap<>();
        final Set<String> hdr10Plus = new LinkedHashSet<>();
        try {
            final MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (MIME_HEVC.equalsIgnoreCase(type)) {
                        for (MediaCodecInfo.CodecProfileLevel profileLevel
                                : info.getCapabilitiesForType(type).profileLevels) {
                            if (profileLevel.profile == HEVC_PROFILE_MAIN10_HDR10_PLUS) {
                                hdr10Plus.add(info.getName());
                            }
                        }
                        continue;
                    }
                    if (!MIME_DOLBY_VISION.equalsIgnoreCase(type)) {
                        continue;
                    }
                    final Map<String, Integer> maxLevels = new LinkedHashMap<>();
                    final MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(type);
                    for (MediaCodecInfo.CodecProfileLevel profileLevel : capabilities.profileLevels) {
                        if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb) {
                            p7 = true;
                        } else if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt) {
                            p8 = true;
                            p8MaxLevel = Math.max(p8MaxLevel, profileLevel.level);
                        }
                        final String name = profileName(profileLevel.profile);
                        if (name != null) {
                            final Integer current = maxLevels.get(name);
                            if (current == null || profileLevel.level > current) {
                                maxLevels.put(name, profileLevel.level);
                            }
                        }
                    }
                    final StringBuilder description = new StringBuilder();
                    for (Map.Entry<String, Integer> entry : maxLevels.entrySet()) {
                        if (description.length() > 0) {
                            description.append(", ");
                        }
                        description.append(entry.getKey()).append(' ').append(levelName(entry.getValue()));
                    }
                    names.put(info.getName(), description.toString());
                }
            }
        } catch (RuntimeException e) {
            // Some devices throw from MediaCodecList: report no support.
        }
        return new DoviDeviceSupport(p7, p8, p8MaxLevel, names, hdr10Plus);
    }
}
