# compose-foundation 1.12.1-raft.1 (patched androidx Compose Foundation for Android)

`third_party/` holds patched builds of third-party dependencies that Raft apps ship with while an
upstream fix is not released. Each entry starts from an exact upstream release, applies the changes
in its directory, is built and verified by a manual workflow, and is published under a `-raft.N`
version to our patched-dependency Maven repository. Nothing here is built by normal CI.

## This entry

| | |
|---|---|
| Upstream | `androidx.compose.foundation:foundation-android:1.12.1`, the official release on [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.12.1/). The app gets it through Compose Multiplatform 1.12.2: `org.jetbrains.compose.foundation:foundation-android:1.12.2` depends on `androidx.compose.foundation:foundation:1.12.1`, whose Android variant is this artifact |
| Patched artifact | `androidx.compose.foundation:foundation-android:1.12.1-raft.1` |
| Not patched | everything else, including the root `androidx.compose.foundation:foundation` module and all `org.jetbrains.compose.*` modules |
| Patch | `RaftAttachGuardPatch.java`: two entry guards written into the official `classes.jar` with ASM 9.11.1. A bytecode patch, not a rebuild (see [How the build works](#how-the-build-works)) |
| Workflow | `.github/workflows/compose-foundation-patch.yml` (`workflow_dispatch` only; `publish` input, default `false`) |
| Repository secrets | the same as [compose-ui-1.12.2](../compose-ui-1.12.2/README.md): `COMPOSE_PATCH_MAVEN_URL`, `COMPOSE_PATCH_MAVEN_USER`, `COMPOSE_PATCH_MAVEN_TOKEN`; read only by the publish step |
| App pin | none yet: it comes in a separate change after the first publish |

The official files, pinned in the workflow. Google Maven's `.sha256` files and the hashes in the
official `.module` have the same values.

| File | SHA-256 |
|---|---|
| `foundation-android-1.12.1.aar` | `444749ea7b5a563e530504b9035c87e47ed0fb62b9f7ba00e90af8aa81e6bfb8` |
| `foundation-android-1.12.1.pom` | `9415263f478c2b6924eab712c427ab887c6d47fb81050aa2efe21049df3af325` |
| `foundation-android-1.12.1.module` | `9326fe08b7a49a920d5cb340e70a8da8259c3e601189ea07242823ca965f1d88` |
| `foundation-android-1.12.1-sources.jar` | `5b58cea5db2583142e4b4b39dce10cbd75722bd06ab35161f06f987be90a53f1` |
| `foundation-android-1.12.1-samples-sources.jar` | `96ff8cb9129ff31a70a0ec19fd5a418576e24b8fa612f072e4207c6ef2f628f6` |

### Why

Android crashes with `IllegalStateException: Cannot read CompositionLocal because the Modifier node
is not currently attached.` (main f467aed8, task #wg-mobile-sync:#197). The bug is in Compose:
`LayoutNode.attach` (ui 1.12.1) applies a moved node's pending modifier update (`applyModifier`)
before `nodes.markAsAttached()`, so a `ModifierNodeElement.update` can run on a node whose
`isAttached` is still `false`. Navigation 3 moves page content as movable content on every push and
pop. When a node in a lazy list row inside that content changes in the same frame, its update
reaches the moved, detached node. Two foundation nodes then read a CompositionLocal and throw:

- `TextFieldCoreModifierNode` (text fields): `updateNode` calls `startCursorJob()`, which reads
  `LocalCursorBlinkEnabled` the first time the caret shows. This is the crash on main.
- `SelectableTextAnnotatedStringNode` (text in a `SelectionContainer`): `update` calls
  `retrievePinnableContainer()`, which reads `LocalPinnableContainer`. The selection crash in 1.22
  (Hands 21abdfad, c561c66a) is this class of crash.

`compose/shared/src/androidUnitTest/kotlin/build/raft/app/ComposeDetachedNodeUpdateUiTest.kt`
reproduces the text-field crash on the official foundation 1.12.1.

### What the patch changes

One guard at the entry of each of two private methods. Nothing else in the AAR changes.

| Method | Guard | Why skipping the body is safe |
|---|---|---|
| `TextFieldCoreModifierNode.startCursorJob()V` | `if (!isAttached) return` | `onAttach()` runs `if (isFocused && showCursor) startCursorJob()`, so a caret start skipped while the node is detached happens when it is attached |
| `SelectableTextAnnotatedStringNode.retrievePinnableContainer()` | `if (!isAttached) return null` | while detached, `update` passes `null` to `SelectionController.updatePinnableContainer`, the value `onDetach()` already set; `onAttach()` reads `LocalPinnableContainer` and sets the container again |

`isAttached` is the public final property `Modifier.Node.isAttached` (JVM `public final boolean
isAttached()` on `androidx/compose/ui/Modifier$Node`). Like kotlinc, the guard calls it through the
class itself (`invokevirtual <class>.isAttached:()Z`). Its bytes are what kotlinc emits for
`if (!isAttached) return` at the start of a method; `AbstractScrollableNode.updateDefaultFlingBehavior`
and `LegacyAdaptingPlatformTextInputModifierNode.launchTextInputSession` in the same jar have the
same guard. One case differs from a node that was never detached: a caret shown only for a
drag-and-drop hover (field not focused) is not started by `onAttach()`; it starts with the next
`updateNode`.

### Upstream status

Not reported yet. Google's androidx (`frameworks/support`) takes changes through Gerrit
(android-review.googlesource.com) and bugs in the Compose component of issuetracker.google.com.
JetBrains/compose-multiplatform-core, which Compose Multiplatform builds from, takes pull requests on
GitHub. The root fix is probably the attach order in ui's `LayoutNode.attach`; the guards only
protect the two nodes that crash for us.

- Google issue: TODO
- Gerrit change: TODO
- JetBrains issue or pull request: TODO

### When to remove

Remove this when an androidx release with the fix (in foundation, or in ui's attach order) reaches
the app, through Compose Multiplatform or a direct androidx dependency. Then delete the app's pin of
`foundation-android:1.12.1-raft.1`, delete this directory and the workflow, and stop publishing
`1.12.1-raft.N`. Run `ComposeDetachedNodeUpdateUiTest` on the new release first.

## How the build works

Unlike `compose-ui-1.12.2`, which rebuilds two klibs from the upstream tag with a source patch, this
entry patches the bytes of the official release. Building androidx foundation from source needs an
AOSP `frameworks/support` checkout with its prebuilts and the release's exact toolchain, and the
result would differ from Google's AAR in every class, so nobody could check that the fix is the only
change. Patching the published AAR keeps every other file and every other class byte-identical to
what Google ships. The whole difference is two short instruction sequences, and `verify_patch.py`
checks exactly that.

The workflow runs on `ubuntu-latest` with JDK 21 (Corretto) and Python 3. It needs no Gradle and no
Android SDK.

1. It downloads the official `.aar`, `.pom`, `.module` and the two sources jars the `.module` lists
   from Google Maven and checks the SHA-256s above. It downloads `org.ow2.asm:asm:9.11.1` from Maven
   Central and checks its pinned SHA-256.
2. `java -cp asm-9.11.1.jar RaftAttachGuardPatch.java <official classes.jar> <patched classes.jar>`
   inserts the two guards.
   - The `ClassWriter` is built from the `ClassReader` with flags 0. The constant pool is copied as
     it is (the `isAttached` method reference is appended), every other method is copied byte for
     byte, and nothing is recomputed.
   - Stack map frames: the method's original frames are kept, and the guard adds one `same_frame`
     right after itself, where the locals are `this` and the stack is empty, as at method entry.
     `COMPUTE_FRAMES` would recompute the frames of the whole method, and merging types needs the
     class hierarchy (Compose UI, coroutines, the Android SDK); a `getCommonSuperClass` guess can
     produce frames the verifier rejects. The patcher's `ClassWriter` fails if it is ever asked for
     a common super class.
   - The guard goes before the first instruction, after the line number at offset 0, so it belongs
     to the method's first line and the local variable `this` covers it.
   - It refuses to run if a class or method is missing, if a method already starts with an
     `isAttached` call, or if a method's first instruction is a branch target or starts a try block.
   - Every other jar entry is copied in the same order with the same bytes, name, timestamp,
     compression method, extra field and comment.
3. `repack_aar.py` writes the AAR again with the patched `classes.jar`: the same entries in the same
   order, with the same timestamps, compression methods and attributes.
4. `javap -c -p` lists both versions of the two classes, taken from the `classes.jar` in each AAR.
   The listings are uploaded as the artifact `compose-foundation-patch-javap`.
5. Verification, run before anything is uploaded:
   - `verify_patch.py --self-test` patches the official AAR in memory the same way, then checks that
     the result passes and that broken variants fail: a wrong jump, a missing or misplaced frame,
     line numbers not moved, an extra constant, another method, class or AAR entry changed, a class
     left unpatched, reordered entries, and edited javap listings.
   - `verify_patch.py` then checks the real output. The AAR and `classes.jar` must have the same
     entries in the same order, all byte-identical except `classes.jar` and the two classes. In each
     class file only appended constant-pool entries for `<class>.isAttached:()Z` and the target
     method's Code attribute may differ: its bytecode must be the guard followed by the original
     bytecode, byte for byte; line numbers, local variables and exception ranges must move by the
     guard's length; the stack map table must be the original one plus the `same_frame` after the
     guard. The javap listings must be identical outside the two methods, and each method must list
     the guard and then its original instructions, moved by 8 (`startCursorJob`) or 9
     (`retrievePinnableContainer`) bytes.
6. `reversion_artifact.py` stages the artifact as `1.12.1-raft.1`: the patched `.aar`, the official
   sources jars unchanged, the `.pom` with only its own `<version>` changed, and the `.module` with
   only the file names and URLs re-versioned and the `.aar`'s size and hashes replaced. Variants,
   attributes, dependencies, constraints and the `component` block (the official root module; Gradle
   reads only its attributes when it consumes this module directly) stay as they are. The script then
   checks the staged files against the official ones. The sources jar is Google's, so it does not
   show the guards.
7. The staged Maven tree is uploaded as the workflow artifact `compose-foundation-1.12.1-raft.1-maven`.
   This happens on every run. The step log lists the SHA-256 of every staged file; the app pin needs
   the `.aar`'s.
8. The upload runs only if `publish` is `true`, and only from the default branch. It uses the
   repository, secrets and upload script of compose-ui, `third_party/compose-ui-1.12.2/upload_maven_dir.sh`:
   - HTTP PUT; it refuses to overwrite a version that already exists, so the next build must use
     `-raft.2`;
   - the credentials go to that one step's `env:`, then into a 0600 netrc file that is deleted when
     the step ends; they never go on a command line, into `$GITHUB_ENV` or into `$GITHUB_OUTPUT`.

Entries are compressed again, so the AAR's bytes and SHA-256 can change with the JDK or zlib version
while its content does not. Pin the SHA-256 of the `.aar` as published (the publish run's log).

To make a new revision, change the patcher and the verifier here and bump `PATCHED_VERSION` in the
workflow.

## Verification

The workflow proves that the published AAR is Google's 1.12.1 plus exactly the two guards, with
stack map frames that match the bytecode. It does not run the patched code. The first Android build
of these classes (D8/R8) and the proof that the crash is gone come with the app pin:
`ComposeDetachedNodeUpdateUiTest` asserts the crash on the official foundation today, and the pin
change turns it into a test that the move completes without the crash.
