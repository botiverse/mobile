# Android Review Recording Guide

Use this guide whenever a task asks for an Android MP4 that demonstrates a
gesture, tap target, drag, scroll, or focus-loss behavior. Reviewers must be
able to see where the tester touched the screen.

## Enable Visible Touches

Before recording, enable Android touch indicators on the target emulator or
device:

```bash
adb -s <serial> shell settings put system show_touches 1
adb -s <serial> shell settings put system pointer_location 1
```

`show_touches` draws the touch dot. `pointer_location` adds the coordinate
trace/overlay; it is visually noisy, but it makes review unambiguous for
tap/drag regressions. Use both unless the task owner explicitly asks for a
clean visual artifact.

To turn the overlays back off:

```bash
adb -s <serial> shell settings put system show_touches 0
adb -s <serial> shell settings put system pointer_location 0
```

## Record an MP4

Prefer native Android screen recording when it works on the target:

```bash
adb -s <serial> shell screenrecord --time-limit 30 /sdcard/review.mp4
adb -s <serial> pull /sdcard/review.mp4 /tmp/review.mp4
```

Run the scenario while `screenrecord` is active. Keep the clip short and show
only the relevant setup, action, and final state.

If native `screenrecord` produces static, corrupt, or very low-frame-rate output
on an emulator, capture rapid screenshots and encode them:

```bash
rm -rf /tmp/review_frames /tmp/review.mp4
mkdir -p /tmp/review_frames
for i in $(seq -w 1 120); do
  adb -s <serial> exec-out screencap -p > /tmp/review_frames/frame_${i}.png
  sleep 0.12
done
ffmpeg -hide_banner -loglevel error \
  -framerate 8 \
  -pattern_type glob \
  -i '/tmp/review_frames/frame_*.png' \
  -vf 'fps=8,format=yuv420p' \
  -movflags +faststart \
  /tmp/review.mp4
```

## Verify Before Posting

Before attaching the MP4 to a task thread:

1. Play or sample the recording locally.
2. Confirm visible touches are present.
3. Confirm the clip shows the initial state, the exact user action, and the
   expected final state.
4. State the device serial, build source, and scenario covered in the thread.

For interaction regressions, do not attach a video that only shows the final
state; reviewers need to see the action that caused it.
