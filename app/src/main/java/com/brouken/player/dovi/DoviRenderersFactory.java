package com.brouken.player.dovi;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link DefaultRenderersFactory} whose video renderer prefers the Dolby Vision decoder for Dolby
 * Vision content even when the decoder declares lower capabilities than the video.
 *
 * <p>Since Media3 1.12, Dolby Vision decoders that don't declare support for the level, size or
 * frame rate of a video are skipped in favour of the plain HEVC decoder. Many phones and tablets
 * declare Dolby Vision profile 8 only up to FHD (e.g. Lenovo Tab P12 Pro: "P8 FHD 30") while they
 * play UHD Dolby Vision fine, so with Media3 1.12 every 4K Dolby Vision video lost Dolby Vision and
 * was played as HDR10. This restores the Media3 1.11 behaviour: decoders are ordered by functional
 * support (MIME type and profile), with the Dolby Vision decoders first.
 */
public class DoviRenderersFactory extends DefaultRenderersFactory {

    /** MIME type the last video decoder was configured with, for diagnostics. */
    @Nullable
    private static volatile String lastVideoCodecMimeType;

    private final boolean tunnelingEnabled;

    /**
     * Returns the MIME type the last video decoder was configured with, e.g. video/dolby-vision or
     * video/hevc: the same decoder name can serve both on some devices.
     */
    @Nullable
    public static String getLastVideoCodecMimeType() {
        return lastVideoCodecMimeType;
    }

    public DoviRenderersFactory(Context context, boolean tunnelingEnabled) {
        super(context);
        this.tunnelingEnabled = tunnelingEnabled;
    }

    @Override
    protected void buildVideoRenderers(Context context, int extensionRendererMode,
                                       MediaCodecSelector mediaCodecSelector, boolean enableDecoderFallback,
                                       Handler eventHandler, VideoRendererEventListener eventListener,
                                       long allowedVideoJoiningTimeMs, ArrayList<Renderer> out) {
        final int start = out.size();
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                eventHandler, eventListener, allowedVideoJoiningTimeMs, out);
        if (tunnelingEnabled) {
            // Keep the default behaviour with tunneling, which needs tunneling capable decoders.
            return;
        }
        for (int i = start; i < out.size(); i++) {
            if (out.get(i).getClass() == MediaCodecVideoRenderer.class) {
                // Same configuration as DefaultRenderersFactory (with its default thresholds).
                final MediaCodecVideoRenderer.Builder builder = new MediaCodecVideoRenderer.Builder(context)
                        .setCodecAdapterFactory(getCodecAdapterFactory())
                        .setMediaCodecSelector(mediaCodecSelector)
                        .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                        .setEnableDecoderFallback(enableDecoderFallback)
                        .setEventHandler(eventHandler)
                        .setEventListener(eventListener)
                        .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);
                out.set(i, new DoviVideoRenderer(builder, context));
                break;
            }
        }
    }

    static final class DoviVideoRenderer extends MediaCodecVideoRenderer {

        private final Context context;

        DoviVideoRenderer(MediaCodecVideoRenderer.Builder builder, Context context) {
            super(builder);
            this.context = context.getApplicationContext();
        }

        @Override
        protected void onCodecInitialized(String name, MediaCodecAdapter.Configuration configuration,
                                          long initializedTimestampMs, long initializationDurationMs) {
            lastVideoCodecMimeType = configuration.codecInfo.codecMimeType;
            super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs);
        }

        @Override
        protected List<MediaCodecInfo> getDecoderInfos(MediaCodecSelector mediaCodecSelector, Format format,
                                                       boolean requiresSecureDecoder)
                throws MediaCodecUtil.DecoderQueryException {
            if (MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)
                    && DoviDeviceSupport.isDisplayDolbyVision(context)) {
                final List<MediaCodecInfo> decoderInfos = MediaCodecUtil.getDecoderInfosSoftMatch(
                        mediaCodecSelector, format, requiresSecureDecoder, /* requiresTunnelingDecoder= */ false);
                if (!decoderInfos.isEmpty()) {
                    return MediaCodecUtil.getDecoderInfosSortedByFormatSupport(context, decoderInfos, format);
                }
            }
            return super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder);
        }
    }
}
