package com.pvp.leaderboard.game;

import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.util.*;

/** The PvP Arena's confirmed leave: "Yes" in the chat menu whose prompt reads "Are you sure you wish to quit this
 *  Duel?", both compared without formatting tags. */
final class LeaveOption
{
    static boolean matches(int component, String option, String prompt)
    {
        return component == InterfaceID.Chatmenu.OPTIONS && option != null && prompt != null
            && "Yes".equals(Text.removeTags(option)) && "Are you sure you wish to quit this Duel?".equals(Text.removeTags(prompt));
    }
}
