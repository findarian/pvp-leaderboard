package com.pvp.leaderboard.overlay;

/** The "PvP Leaderboard" last line of a box: shown for the box's first minute on screen in a client run, or always. */
public final class Footer
{
	public static final String TEXT = "PvP Leaderboard";
	long left = 60_000;
	private long last = -1;

	/** A frame of the box at {@code now}: whether it ends with the line. Frames more than a second apart count no time
	 *  between them. */
	public boolean shown(long now, boolean always)
	{
		if (last >= 0 && now - last <= 1_000) left -= Math.max(0, now - last);
		last = now;
		return always || left > 0;
	}
}
