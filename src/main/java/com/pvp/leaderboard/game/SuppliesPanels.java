package com.pvp.leaderboard.game;

import static net.runelite.api.gameval.InterfaceID.PvpArenaStagingareaSupplies.*;

/** The PvP Arena supplies chest's three kit panels (same layout as the duel screen's, see {@link ArenaWidgets}). */
final class SuppliesPanels
{
    static final ArenaWidgets.KitPanel[] PANELS = {
        new ArenaWidgets.KitPanel(new int[]{_0SLOT0, _0SLOT1, _0SLOT2, _0SLOT3, _0SLOT4, _0SLOT5, _0SLOT7, _0SLOT9, _0SLOT10, _0SLOT12, _0SLOT13},
            _0EQUIPMENT, _0INVENTORY, _0SPELLBOOK_CONTAINER, _0SPELLBOOK_MENU, _0SPELLBOOK_DISPLAY, _0ITEMS_LIST, _0ITEMS_SCROLLBAR, _0SEEK),
        new ArenaWidgets.KitPanel(new int[]{_1SLOT0, _1SLOT1, _1SLOT2, _1SLOT3, _1SLOT4, _1SLOT5, _1SLOT7, _1SLOT9, _1SLOT10, _1SLOT12, _1SLOT13},
            _1EQUIPMENT, _1INVENTORY, _1SPELLBOOK_CONTAINER, _1SPELLBOOK_MENU, _1SPELLBOOK_DISPLAY, _1ITEMS_LIST, _1ITEMS_SCROLLBAR, _1SEEK),
        new ArenaWidgets.KitPanel(new int[]{_2SLOT0, _2SLOT1, _2SLOT2, _2SLOT3, _2SLOT4, _2SLOT5, _2SLOT7, _2SLOT9, _2SLOT10, _2SLOT12, _2SLOT13},
            _2EQUIPMENT, _2INVENTORY, _2SPELLBOOK_CONTAINER, _2SPELLBOOK_MENU, _2SPELLBOOK_DISPLAY, _2ITEMS_LIST, _2ITEMS_SCROLLBAR, _2SEEK),
    };

    private SuppliesPanels()
    {
    }
}
