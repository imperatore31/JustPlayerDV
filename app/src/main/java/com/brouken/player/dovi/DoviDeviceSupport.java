package com.brouken.player.dovi;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import java.util.LinkedHashSet;
import java.util.Set;

/** Reports which Dolby Vision HEVC profiles the device decoders support. */
public final class DoviDeviceSupport {

    private static final String MIME_DOLBY_VISION = "video/dolby-vision";

    private static volatile DoviDeviceSupport instance;

    public final boolean profile7;
    public final boolean profile8;
    public final Set<String> decoders;

    private DoviDeviceSupport(boolean profile7, boolean profile8, Set<String> decoders) {
        this.profile7 = profile7;
        this.profile8 = profile8;
        this.decoders = decoders;
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

    private static DoviDeviceSupport query() {
        boolean p7 = false;
        boolean p8 = false;
        final Set<String> names = new LinkedHashSet<>();
        try {
            final MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (!MIME_DOLBY_VISION.equalsIgnoreCase(type)) {
                        continue;
                    }
                    names.add(info.getName());
                    final MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(type);
                    for (MediaCodecInfo.CodecProfileLevel profileLevel : capabilities.profileLevels) {
                        if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb) {
                            p7 = true;
                        } else if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt) {
                            p8 = true;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // Some devices throw from MediaCodecList: report no support.
        }
        return new DoviDeviceSupport(p7, p8, names);
    }
}
