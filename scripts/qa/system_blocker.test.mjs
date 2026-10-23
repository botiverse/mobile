import test from "node:test";
import assert from "node:assert/strict";
import { detectForeignAndroidSystemBlocker } from "./system_blocker.mjs";

test("Pixel Launcher and System UI ANRs are foreign harness blockers", () => {
  assert.deepEqual(
    detectForeignAndroidSystemBlocker([
      { text: "Pixel Launcher isn't responding", desc: "", pkg: "android", resourceId: "android:id/alertTitle" },
      { text: "Wait", desc: "", pkg: "android", resourceId: "android:id/aerr_wait" },
    ]),
    { code: "pixel_launcher_anr", label: "Pixel Launcher isn't responding" },
  );
  assert.equal(
    detectForeignAndroidSystemBlocker([
      { text: "", desc: "System UI is not responding", pkg: "com.android.systemui", resourceId: "android:id/alertTitle" },
      { text: "Close app", desc: "", pkg: "android", resourceId: "android:id/aerr_close" },
    ])?.code,
    "system_ui_anr",
  );
});

test("the app-under-test or a generic ANR is not rewritten as harness error", () => {
  const systemButtons = [{ text: "Wait", pkg: "android", resourceId: "android:id/aerr_wait" }];
  assert.equal(detectForeignAndroidSystemBlocker([
    { text: "Raft isn't responding", pkg: "android", resourceId: "android:id/alertTitle" },
    ...systemButtons,
  ]), null);
  assert.equal(detectForeignAndroidSystemBlocker([
    { text: "This app isn't responding", pkg: "android", resourceId: "android:id/alertTitle" },
    ...systemButtons,
  ]), null);
  assert.equal(detectForeignAndroidSystemBlocker([{ text: "Channel failed to load", pkg: "build.raft.app" }]), null);
});

test("foreign ANR wording inside Raft content cannot impersonate a system dialog", () => {
  assert.equal(detectForeignAndroidSystemBlocker([
    { text: "Pixel Launcher isn't responding", pkg: "build.raft.app", resourceId: "" },
    { text: "Wait", pkg: "build.raft.app", resourceId: "" },
  ]), null);
  assert.equal(detectForeignAndroidSystemBlocker([
    { text: "Pixel Launcher isn't responding", pkg: "android", resourceId: "android:id/alertTitle" },
    { text: "Wait", pkg: "build.raft.app", resourceId: "" },
  ]), null);
});
