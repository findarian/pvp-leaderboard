package com.pvp.leaderboard.lobby;

/**
 * Fight styles the lobby supports. Matches the server's
 * {@code ALLOWED_STYLES = {nh,veng,multi,dmm}} validation set.
 *
 * <p>Declaration order is the on-screen order used by the pre-lobby
 * gate's style-toggle row and the per-row chip strip — keep it stable
 * so the UI doesn't reflow on enum reordering. {@code EnumSet}
 * iteration also follows declaration order, so any CSV rendering of
 * "selected styles" reads NH-first / DMM-last by default.
 *
 * <p>Field {@link #label} is intentionally {@code public final} (not
 * encapsulated) — these are tight-knit value types, the existing
 * rendering code accesses {@code style.label} directly, and switching
 * to a getter would buy nothing but verbosity at call sites.
 */
public enum Style
{
    NH("NH"),
    VENG("Veng"),
    MULTI("Multi"),
    DMM("DMM");

    public final String label;

    Style(String label)
    {
        this.label = label;
    }
}
