package com.brouken.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.brouken.player.audio.RoomParams;
import com.brouken.player.audio.RoomPreset;

/**
 * Live tuning of the headphone virtual surround, from the player: room preset, then every value
 * of the room, heard a moment after each change. Moving a slider makes the tuned room the custom
 * room ({@link RoomPreset#CUSTOM}), saved in the preferences.
 */
final class VirtualSurroundTuning {

    interface Listener {
        void onRoomChanged(RoomParams room);

        void onDialogueChanged(int decibels);

        void onEffectToggled(boolean on);

        void onClosed();
    }

    /** Waiting time after a slider move before rebuilding the room (it takes a moment). */
    private static final long APPLY_DELAY_MS = 350;
    private static final int[] DIALOGUE_LEVELS = {0, 3, 6};

    private final Activity activity;
    private final Prefs prefs;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable apply = this::apply;
    private final RoomPreset[] presets = RoomPreset.values();

    private RoomPreset preset;
    private RoomParams room;
    private Spinner presetSpinner;
    private boolean updatingViews;
    private boolean applyPending;

    private Slider distance;
    private Slider size;
    private Slider reverb;
    private Slider level;
    private Slider width;
    private Slider brightness;
    private Slider height;

    private VirtualSurroundTuning(Activity activity, Prefs prefs, Listener listener) {
        this.activity = activity;
        this.prefs = prefs;
        this.listener = listener;
        preset = RoomPreset.fromKey(prefs.virtualSurroundRoom);
        room = preset.resolve(prefs.virtualSurroundCustom);
    }

    static AlertDialog show(Activity activity, Prefs prefs, boolean effectOn, Listener listener) {
        return new VirtualSurroundTuning(activity, prefs, listener).show(effectOn);
    }

    private AlertDialog show(boolean effectOn) {
        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        final int padding = Utils.dpToPx(20);
        layout.setPadding(padding, Utils.dpToPx(8), padding, Utils.dpToPx(8));

        final TextView hint = new TextView(activity);
        hint.setText(R.string.virtual_surround_tuning_hint);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setAlpha(0.8f);
        layout.addView(hint);

        final Switch effect = new Switch(activity);
        effect.setText(R.string.virtual_surround_tuning_effect);
        effect.setChecked(effectOn);
        effect.setPadding(0, Utils.dpToPx(12), 0, Utils.dpToPx(4));
        effect.setOnCheckedChangeListener((button, checked) -> listener.onEffectToggled(checked));
        layout.addView(effect);

        final TextView presetLabel = label(layout);
        presetLabel.setText(R.string.virtual_surround_tuning_room);
        presetSpinner = new Spinner(activity);
        final ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item,
                activity.getResources().getTextArray(R.array.virtual_surround_room_entries));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(adapter);
        presetSpinner.setSelection(preset.ordinal());
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingViews || presets[position] == preset) {
                    return;
                }
                preset = presets[position];
                room = preset.resolve(prefs.virtualSurroundCustom);
                updateSliders();
                handler.removeCallbacks(apply);
                apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        layout.addView(presetSpinner);

        distance = new Slider(layout, RoomParams.MIN_DISTANCE, RoomParams.MAX_DISTANCE, 0.1,
                v -> activity.getString(R.string.virtual_surround_tuning_distance, v),
                v -> room = room.withDistance(v));
        size = new Slider(layout, RoomParams.MIN_SIZE, RoomParams.MAX_SIZE, 0.05,
                v -> activity.getString(R.string.virtual_surround_tuning_size, room.withSize(v).describeSize()),
                v -> room = room.withSize(v));
        reverb = new Slider(layout, RoomParams.MIN_REVERB, RoomParams.MAX_REVERB, 0.01,
                v -> activity.getString(R.string.virtual_surround_tuning_reverb, v),
                v -> room = room.withReverbTime(v));
        level = new Slider(layout, RoomParams.MIN_ROOM_LEVEL, RoomParams.MAX_ROOM_LEVEL, 0.5,
                v -> activity.getString(R.string.virtual_surround_tuning_level, v),
                v -> room = room.withRoomLevel(v));
        width = new Slider(layout, RoomParams.MIN_FRONT_WIDTH, RoomParams.MAX_FRONT_WIDTH, 1,
                v -> activity.getString(R.string.virtual_surround_tuning_width, v),
                v -> room = room.withFrontWidth(v));
        brightness = new Slider(layout, RoomParams.MIN_BRIGHTNESS, RoomParams.MAX_BRIGHTNESS, 0.5,
                v -> activity.getString(R.string.virtual_surround_tuning_brightness, v),
                v -> room = room.withBrightness(v));
        final double[] heights = RoomParams.SCREEN_HEIGHTS;
        height = new Slider(layout, heights[0], heights[heights.length - 1], heights[1] - heights[0],
                v -> activity.getString(R.string.virtual_surround_tuning_height, v),
                v -> room = room.withScreenHeight(v));
        updateSliders();

        // Dialogue boost (also in the settings)
        final TextView dialogueLabel = label(layout);
        final SeekBar dialogue = new SeekBar(activity);
        dialogue.setMax(DIALOGUE_LEVELS.length - 1);
        int dialogueIndex = 0;
        for (int i = 0; i < DIALOGUE_LEVELS.length; i++) {
            if (DIALOGUE_LEVELS[i] == prefs.virtualSurroundDialogue) {
                dialogueIndex = i;
            }
        }
        dialogue.setProgress(dialogueIndex);
        dialogueLabel.setText(activity.getString(R.string.virtual_surround_tuning_dialogue, DIALOGUE_LEVELS[dialogueIndex]));
        dialogue.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                final int decibels = DIALOGUE_LEVELS[progress];
                dialogueLabel.setText(activity.getString(R.string.virtual_surround_tuning_dialogue, decibels));
                if (fromUser) {
                    prefs.updateVirtualSurroundDialogue(decibels);
                    listener.onDialogueChanged(decibels);
                }
            }
        });
        layout.addView(dialogue);

        final ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(layout);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.virtual_surround_tuning_title)
                .setView(scrollView)
                .setPositiveButton(R.string.virtual_surround_tuning_close, null)
                .create();
        dialog.setOnDismissListener(d -> {
            // Do not lose the last change
            if (applyPending) {
                handler.removeCallbacks(apply);
                apply();
            }
            listener.onClosed();
        });
        dialog.show();
        return dialog;
    }

    /** A value of the room was changed by the user: the tuned room becomes the custom room. */
    private void onUserChange() {
        if (preset != RoomPreset.CUSTOM) {
            preset = RoomPreset.CUSTOM;
            updatingViews = true;
            presetSpinner.setSelection(preset.ordinal());
            presetSpinner.post(() -> updatingViews = false);
        }
        handler.removeCallbacks(apply);
        handler.postDelayed(apply, APPLY_DELAY_MS);
        applyPending = true;
    }

    private void apply() {
        applyPending = false;
        if (preset == RoomPreset.CUSTOM) {
            prefs.updateVirtualSurroundCustom(room);
        }
        prefs.updateVirtualSurroundRoom(preset.key);
        listener.onRoomChanged(room);
    }

    private void updateSliders() {
        distance.set(room.distance);
        size.set(room.size);
        reverb.set(room.reverbTime);
        level.set(room.roomLevelDb);
        width.set(room.frontWidth);
        brightness.set(room.brightnessDb);
        height.set(room.screenHeight);
    }

    private TextView label(LinearLayout layout) {
        final TextView label = new TextView(activity);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setPadding(0, Utils.dpToPx(12), 0, 0);
        layout.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return label;
    }

    private interface Format {
        String format(double value);
    }

    private interface Change {
        void change(double value);
    }

    /** Label and seek bar for one value. */
    private final class Slider {
        private final double min;
        private final double step;
        private final SeekBar seekBar;
        private final TextView label;
        private final Format format;

        Slider(LinearLayout layout, double min, double max, double step, Format format, Change change) {
            this.min = min;
            this.step = step;
            this.format = format;
            label = label(layout);
            seekBar = new SeekBar(activity);
            seekBar.setMax((int) Math.round((max - min) / step));
            seekBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    final double value = value(progress);
                    if (fromUser) {
                        change.change(value);
                        onUserChange();
                    }
                    label.setText(format.format(value));
                }
            });
            layout.addView(seekBar);
        }

        private double value(int progress) {
            return min + progress * step;
        }

        void set(double value) {
            final int progress = (int) Math.round((value - min) / step);
            seekBar.setProgress(progress);
            label.setText(format.format(value(progress)));
        }
    }

    private abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
