package build.raft.app

/*
 * An iPad (or iPhone) with a hardware keyboard attached: a Magic Keyboard, a Smart Keyboard, a
 * Bluetooth keyboard. The iOS actuals read GameController's `GCKeyboard.coalescedKeyboard` at
 * runtime (RaftIosHardwareKeyboard in iosMain) and pass it here, so the decisions are unit-tested.
 * Without a hardware keyboard every value is the touch-only iOS one. The Mac (RaftIosAppOnMac.kt)
 * has its own mapping and never reaches these.
 */

/**
 * The tallest keyboard overlap, in points, that is still the shortcuts bar a hardware keyboard
 * leaves on screen, not a software keyboard. iPadOS reports a keyboard frame for that bar (about
 * 50-70pt, INFERRED from platform behaviour, not logged on a device), but since iPadOS 15 it draws
 * it as a small floating pill, so lifting the composer by the frame left an empty band above the
 * pill. The smallest iPad software keyboard (undocked or split aside, which reports no overlap) is
 * well over 200pt, so 100pt separates the two.
 */
internal const val RaftIosHardwareKeyboardShortcutsBarMaxPoints: Double = 100.0

/**
 * Whether the page ignores a keyboard frame that covers [overlapPoints] of it: only the shortcuts
 * bar of a connected hardware keyboard (below [RaftIosHardwareKeyboardShortcutsBarMaxPoints]).
 * Without a hardware keyboard every frame lifts the composer, as before.
 */
internal fun raftIosIgnoresKeyboardFrame(hardwareKeyboard: Boolean, overlapPoints: Double): Boolean =
    hardwareKeyboard && overlapPoints > 0.0 && overlapPoints < RaftIosHardwareKeyboardShortcutsBarMaxPoints
