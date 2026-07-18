import assert from "node:assert/strict";
import test from "node:test";

import {
  hasOpenLensIdentity,
  hasOpenVisualIdentity,
  stableLensKey,
} from "./identity.mjs";

test("visual issue identity ignores a changed perceptual hash", () => {
  const markers = [{ caseId: "components.ui.button.states", comparison: "react__android", hash: "p1:aaaa" }];
  assert.equal(hasOpenVisualIdentity(markers, "components.ui.button.states", "react__android"), true);
  assert.equal(hasOpenVisualIdentity(markers, "components.ui.button.states", "react__ios"), false);
});

test("lens identity accepts legacy keys that embedded a snippet hash", () => {
  const markers = [{ lens: "token-audit", key: "compose/shared/Foo.kt#0123456789abcdef" }];
  assert.equal(stableLensKey(markers[0].key), "compose/shared/Foo.kt");
  assert.equal(hasOpenLensIdentity(markers, "token-audit", "compose/shared/Foo.kt"), true);
  assert.equal(hasOpenLensIdentity(markers, "font-audit", "compose/shared/Foo.kt"), false);
});
