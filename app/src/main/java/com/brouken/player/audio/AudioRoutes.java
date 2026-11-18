package com.brouken.player.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

/** Which kinds of audio outputs are connected. */
public final class AudioRoutes {

    private AudioRoutes() {
    }

    /** Whether headphones (wired, USB or Bluetooth) are connected: media audio goes to them. */
    public static boolean hasHeadphones(Context context) {
        for (AudioDeviceInfo device : getOutputs(context)) {
            if (isHeadphones(device.getType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an output that may play multichannel audio itself is connected (TV, AV receiver,
     * soundbar): virtual surround would replace its surround sound with a stereo mix.
     */
    public static boolean hasExternalOutput(Context context) {
        for (AudioDeviceInfo device : getOutputs(context)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_HDMI:
                case AudioDeviceInfo.TYPE_HDMI_ARC:
                case AudioDeviceInfo.TYPE_HDMI_EARC:
                case AudioDeviceInfo.TYPE_LINE_ANALOG:
                case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                case AudioDeviceInfo.TYPE_AUX_LINE:
                case AudioDeviceInfo.TYPE_DOCK:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    private static boolean isHeadphones(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            // USB-C earphones and DAC dongles often report themselves as generic USB audio devices
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_HEARING_AID:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                return true;
            default:
                return false;
        }
    }

    private static AudioDeviceInfo[] getOutputs(Context context) {
        final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return new AudioDeviceInfo[0];
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
    }
}
