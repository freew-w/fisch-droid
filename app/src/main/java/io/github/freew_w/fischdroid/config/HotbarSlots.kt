package io.github.freew_w.fischdroid.config

/**
 * Hotbar cell centres tapped before a cast: the Bestiary item twice (equip, then unequip, which
 * closes a popup that swallowed the cast tap) and the fishing rod once (equip it). Both slots are
 * mandatory: there is no "unset" state, so the burst always taps the configured points.
 */
data class HotbarSlots(
    val rodX: Int,
    val rodY: Int,
    val bestiaryX: Int,
    val bestiaryY: Int,
)
