package com.pvp.leaderboard.game;

import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

public final class ArenaWidgets
{
    private ArenaWidgets() {}

    public static final String[] BUILD_ORDER = {"main", "zerker", "pure"};
    public static final int[] WORN_SLOT_INDEX = {0, 1, 2, 3, 4, 5, 7, 9, 10, 12, 13};
    public static final String[] WORN_SLOT_NAMES = {"head", "cape", "neck", "weapon", "body", "shield", "legs", "hands", "feet", "ring", "ammo"};
    public static final int BLANK_ITEM_ID = ItemID.BLANKOBJECT;

    public static final int DUEL_GROUP = InterfaceID.PVP_ARENA_UNRANKEDDUEL;
    public static final int SUPPLIES_GROUP = InterfaceID.PVP_ARENA_STAGINGAREA_SUPPLIES;
    public static final int RUNEPOUCH_GROUP = InterfaceID.PVP_ARENA_RUNEPOUCH;
    public static final int SHARELOADOUT_GROUP = InterfaceID.PVP_ARENA_STAGINGAREA_SHARELOADOUT;
    public static final int BANK_GROUP = InterfaceID.BANKMAIN;

    public static final int DUEL_ROOT = InterfaceID.PvpArenaUnrankedduel.UNIVERSE;
    public static final int DUEL_TITLE = InterfaceID.PvpArenaUnrankedduel.TITLE;
    public static final int DUEL_TAB_MY = InterfaceID.PvpArenaUnrankedduel.TAB_MYEQUIPMENT;
    public static final int DUEL_BUILD_CONFIRM = InterfaceID.PvpArenaUnrankedduel.BUILD_CONFIRM;
    public static final int DUEL_OPPONENT_CONFIRM = InterfaceID.PvpArenaUnrankedduel.OPPONENT_CONFIRM;
    public static final int[] DUEL_CONFIRMS = {DUEL_BUILD_CONFIRM, DUEL_OPPONENT_CONFIRM};

    public static final int SUPPLIES_ROOT = InterfaceID.PvpArenaStagingareaSupplies.UNIVERSE;
    public static final int RUNEPOUCH_ROOT = InterfaceID.PvpArenaRunepouch.UNIVERSE;
    public static final int RUNEPOUCH_RUNES = InterfaceID.PvpArenaRunepouch.RUNES;
    public static final int SHARELOADOUT_COPY = InterfaceID.PvpArenaStagingareaShareloadout.COPY;

    public static final int ARENA_WORLD_VARBIT = VarbitID.THIS_IS_A_PVP_ARENA_WORLD;
    public static final int[] LOADOUT_SPELLBOOK_VARBITS = {VarbitID.PVPA_LOADOUT_A_SPELLBOOK, VarbitID.PVPA_LOADOUT_B_SPELLBOOK, VarbitID.PVPA_LOADOUT_C_SPELLBOOK};
    public static final int[] LOADOUT_POUCH_VARPS = {VarPlayerID.PVPA_LOADOUT_A_RUNEPOUCH, VarPlayerID.PVPA_LOADOUT_B_RUNEPOUCH, VarPlayerID.PVPA_LOADOUT_C_RUNEPOUCH};

    public static final class KitPanel
    {
        public final int equipment;
        public final int[] slots;
        public final int inventory;
        public final int spellbookContainer;
        public final int spellbookMenu;
        public final int spellbookDisplay;
        public final int itemsList;
        public final int itemsScrollbar;
        public final int seek;

        KitPanel(int equipment, int[] slots, int inventory, int spellbookContainer, int spellbookMenu, int spellbookDisplay,
                 int itemsList, int itemsScrollbar, int seek)
        {
            this.equipment = equipment;
            this.slots = slots;
            this.inventory = inventory;
            this.spellbookContainer = spellbookContainer;
            this.spellbookMenu = spellbookMenu;
            this.spellbookDisplay = spellbookDisplay;
            this.itemsList = itemsList;
            this.itemsScrollbar = itemsScrollbar;
            this.seek = seek;
        }
    }

    public static final KitPanel[] DUEL_PANELS = {
        new KitPanel(InterfaceID.PvpArenaUnrankedduel._0EQUIPMENT,
            new int[]{InterfaceID.PvpArenaUnrankedduel._0SLOT0, InterfaceID.PvpArenaUnrankedduel._0SLOT1, InterfaceID.PvpArenaUnrankedduel._0SLOT2,
                InterfaceID.PvpArenaUnrankedduel._0SLOT3, InterfaceID.PvpArenaUnrankedduel._0SLOT4, InterfaceID.PvpArenaUnrankedduel._0SLOT5,
                InterfaceID.PvpArenaUnrankedduel._0SLOT7, InterfaceID.PvpArenaUnrankedduel._0SLOT9, InterfaceID.PvpArenaUnrankedduel._0SLOT10,
                InterfaceID.PvpArenaUnrankedduel._0SLOT12, InterfaceID.PvpArenaUnrankedduel._0SLOT13},
            InterfaceID.PvpArenaUnrankedduel._0INVENTORY, InterfaceID.PvpArenaUnrankedduel._0SPELLBOOK_CONTAINER,
            InterfaceID.PvpArenaUnrankedduel._0SPELLBOOK_MENU, InterfaceID.PvpArenaUnrankedduel._0SPELLBOOK_DISPLAY,
            InterfaceID.PvpArenaUnrankedduel._0ITEMS_LIST, InterfaceID.PvpArenaUnrankedduel._0ITEMS_SCROLLBAR, InterfaceID.PvpArenaUnrankedduel._0SEEK),
        new KitPanel(InterfaceID.PvpArenaUnrankedduel._1EQUIPMENT,
            new int[]{InterfaceID.PvpArenaUnrankedduel._1SLOT0, InterfaceID.PvpArenaUnrankedduel._1SLOT1, InterfaceID.PvpArenaUnrankedduel._1SLOT2,
                InterfaceID.PvpArenaUnrankedduel._1SLOT3, InterfaceID.PvpArenaUnrankedduel._1SLOT4, InterfaceID.PvpArenaUnrankedduel._1SLOT5,
                InterfaceID.PvpArenaUnrankedduel._1SLOT7, InterfaceID.PvpArenaUnrankedduel._1SLOT9, InterfaceID.PvpArenaUnrankedduel._1SLOT10,
                InterfaceID.PvpArenaUnrankedduel._1SLOT12, InterfaceID.PvpArenaUnrankedduel._1SLOT13},
            InterfaceID.PvpArenaUnrankedduel._1INVENTORY, InterfaceID.PvpArenaUnrankedduel._1SPELLBOOK_CONTAINER,
            InterfaceID.PvpArenaUnrankedduel._1SPELLBOOK_MENU, InterfaceID.PvpArenaUnrankedduel._1SPELLBOOK_DISPLAY,
            InterfaceID.PvpArenaUnrankedduel._1ITEMS_LIST, InterfaceID.PvpArenaUnrankedduel._1ITEMS_SCROLLBAR, InterfaceID.PvpArenaUnrankedduel._1SEEK),
        new KitPanel(InterfaceID.PvpArenaUnrankedduel._2EQUIPMENT,
            new int[]{InterfaceID.PvpArenaUnrankedduel._2SLOT0, InterfaceID.PvpArenaUnrankedduel._2SLOT1, InterfaceID.PvpArenaUnrankedduel._2SLOT2,
                InterfaceID.PvpArenaUnrankedduel._2SLOT3, InterfaceID.PvpArenaUnrankedduel._2SLOT4, InterfaceID.PvpArenaUnrankedduel._2SLOT5,
                InterfaceID.PvpArenaUnrankedduel._2SLOT7, InterfaceID.PvpArenaUnrankedduel._2SLOT9, InterfaceID.PvpArenaUnrankedduel._2SLOT10,
                InterfaceID.PvpArenaUnrankedduel._2SLOT12, InterfaceID.PvpArenaUnrankedduel._2SLOT13},
            InterfaceID.PvpArenaUnrankedduel._2INVENTORY, InterfaceID.PvpArenaUnrankedduel._2SPELLBOOK_CONTAINER,
            InterfaceID.PvpArenaUnrankedduel._2SPELLBOOK_MENU, InterfaceID.PvpArenaUnrankedduel._2SPELLBOOK_DISPLAY,
            InterfaceID.PvpArenaUnrankedduel._2ITEMS_LIST, InterfaceID.PvpArenaUnrankedduel._2ITEMS_SCROLLBAR, InterfaceID.PvpArenaUnrankedduel._2SEEK),
    };

    public static final KitPanel[] SUPPLIES_PANELS = {
        new KitPanel(InterfaceID.PvpArenaStagingareaSupplies._0EQUIPMENT,
            new int[]{InterfaceID.PvpArenaStagingareaSupplies._0SLOT0, InterfaceID.PvpArenaStagingareaSupplies._0SLOT1, InterfaceID.PvpArenaStagingareaSupplies._0SLOT2,
                InterfaceID.PvpArenaStagingareaSupplies._0SLOT3, InterfaceID.PvpArenaStagingareaSupplies._0SLOT4, InterfaceID.PvpArenaStagingareaSupplies._0SLOT5,
                InterfaceID.PvpArenaStagingareaSupplies._0SLOT7, InterfaceID.PvpArenaStagingareaSupplies._0SLOT9, InterfaceID.PvpArenaStagingareaSupplies._0SLOT10,
                InterfaceID.PvpArenaStagingareaSupplies._0SLOT12, InterfaceID.PvpArenaStagingareaSupplies._0SLOT13},
            InterfaceID.PvpArenaStagingareaSupplies._0INVENTORY, InterfaceID.PvpArenaStagingareaSupplies._0SPELLBOOK_CONTAINER,
            InterfaceID.PvpArenaStagingareaSupplies._0SPELLBOOK_MENU, InterfaceID.PvpArenaStagingareaSupplies._0SPELLBOOK_DISPLAY,
            InterfaceID.PvpArenaStagingareaSupplies._0ITEMS_LIST, InterfaceID.PvpArenaStagingareaSupplies._0ITEMS_SCROLLBAR, InterfaceID.PvpArenaStagingareaSupplies._0SEEK),
        new KitPanel(InterfaceID.PvpArenaStagingareaSupplies._1EQUIPMENT,
            new int[]{InterfaceID.PvpArenaStagingareaSupplies._1SLOT0, InterfaceID.PvpArenaStagingareaSupplies._1SLOT1, InterfaceID.PvpArenaStagingareaSupplies._1SLOT2,
                InterfaceID.PvpArenaStagingareaSupplies._1SLOT3, InterfaceID.PvpArenaStagingareaSupplies._1SLOT4, InterfaceID.PvpArenaStagingareaSupplies._1SLOT5,
                InterfaceID.PvpArenaStagingareaSupplies._1SLOT7, InterfaceID.PvpArenaStagingareaSupplies._1SLOT9, InterfaceID.PvpArenaStagingareaSupplies._1SLOT10,
                InterfaceID.PvpArenaStagingareaSupplies._1SLOT12, InterfaceID.PvpArenaStagingareaSupplies._1SLOT13},
            InterfaceID.PvpArenaStagingareaSupplies._1INVENTORY, InterfaceID.PvpArenaStagingareaSupplies._1SPELLBOOK_CONTAINER,
            InterfaceID.PvpArenaStagingareaSupplies._1SPELLBOOK_MENU, InterfaceID.PvpArenaStagingareaSupplies._1SPELLBOOK_DISPLAY,
            InterfaceID.PvpArenaStagingareaSupplies._1ITEMS_LIST, InterfaceID.PvpArenaStagingareaSupplies._1ITEMS_SCROLLBAR, InterfaceID.PvpArenaStagingareaSupplies._1SEEK),
        new KitPanel(InterfaceID.PvpArenaStagingareaSupplies._2EQUIPMENT,
            new int[]{InterfaceID.PvpArenaStagingareaSupplies._2SLOT0, InterfaceID.PvpArenaStagingareaSupplies._2SLOT1, InterfaceID.PvpArenaStagingareaSupplies._2SLOT2,
                InterfaceID.PvpArenaStagingareaSupplies._2SLOT3, InterfaceID.PvpArenaStagingareaSupplies._2SLOT4, InterfaceID.PvpArenaStagingareaSupplies._2SLOT5,
                InterfaceID.PvpArenaStagingareaSupplies._2SLOT7, InterfaceID.PvpArenaStagingareaSupplies._2SLOT9, InterfaceID.PvpArenaStagingareaSupplies._2SLOT10,
                InterfaceID.PvpArenaStagingareaSupplies._2SLOT12, InterfaceID.PvpArenaStagingareaSupplies._2SLOT13},
            InterfaceID.PvpArenaStagingareaSupplies._2INVENTORY, InterfaceID.PvpArenaStagingareaSupplies._2SPELLBOOK_CONTAINER,
            InterfaceID.PvpArenaStagingareaSupplies._2SPELLBOOK_MENU, InterfaceID.PvpArenaStagingareaSupplies._2SPELLBOOK_DISPLAY,
            InterfaceID.PvpArenaStagingareaSupplies._2ITEMS_LIST, InterfaceID.PvpArenaStagingareaSupplies._2ITEMS_SCROLLBAR, InterfaceID.PvpArenaStagingareaSupplies._2SEEK),
    };

    public static final int[][] LOADOUT_VARBITS = {
        {VarbitID.PVPA_LOADOUT_A_INV_00, VarbitID.PVPA_LOADOUT_A_INV_01, VarbitID.PVPA_LOADOUT_A_INV_02, VarbitID.PVPA_LOADOUT_A_INV_03,
            VarbitID.PVPA_LOADOUT_A_INV_04, VarbitID.PVPA_LOADOUT_A_INV_05, VarbitID.PVPA_LOADOUT_A_INV_06, VarbitID.PVPA_LOADOUT_A_INV_07,
            VarbitID.PVPA_LOADOUT_A_INV_08, VarbitID.PVPA_LOADOUT_A_INV_09, VarbitID.PVPA_LOADOUT_A_INV_10, VarbitID.PVPA_LOADOUT_A_INV_11,
            VarbitID.PVPA_LOADOUT_A_INV_12, VarbitID.PVPA_LOADOUT_A_INV_13, VarbitID.PVPA_LOADOUT_A_INV_14, VarbitID.PVPA_LOADOUT_A_INV_15,
            VarbitID.PVPA_LOADOUT_A_INV_16, VarbitID.PVPA_LOADOUT_A_INV_17, VarbitID.PVPA_LOADOUT_A_INV_18, VarbitID.PVPA_LOADOUT_A_INV_19,
            VarbitID.PVPA_LOADOUT_A_INV_20, VarbitID.PVPA_LOADOUT_A_INV_21, VarbitID.PVPA_LOADOUT_A_INV_22, VarbitID.PVPA_LOADOUT_A_INV_23,
            VarbitID.PVPA_LOADOUT_A_INV_24, VarbitID.PVPA_LOADOUT_A_INV_25, VarbitID.PVPA_LOADOUT_A_INV_26, VarbitID.PVPA_LOADOUT_A_INV_27,
            VarbitID.PVPA_LOADOUT_A_WORN_HAT, VarbitID.PVPA_LOADOUT_A_WORN_BACK, VarbitID.PVPA_LOADOUT_A_WORN_FRONT, VarbitID.PVPA_LOADOUT_A_WORN_RHAND,
            VarbitID.PVPA_LOADOUT_A_WORN_TORSO, VarbitID.PVPA_LOADOUT_A_WORN_LHAND, VarbitID.PVPA_LOADOUT_A_WORN_LEGS, VarbitID.PVPA_LOADOUT_A_WORN_HANDS,
            VarbitID.PVPA_LOADOUT_A_WORN_FEET, VarbitID.PVPA_LOADOUT_A_WORN_RING, VarbitID.PVPA_LOADOUT_A_WORN_QUIVER, VarbitID.PVPA_LOADOUT_A_SPELLBOOK},
        {VarbitID.PVPA_LOADOUT_B_INV_00, VarbitID.PVPA_LOADOUT_B_INV_01, VarbitID.PVPA_LOADOUT_B_INV_02, VarbitID.PVPA_LOADOUT_B_INV_03,
            VarbitID.PVPA_LOADOUT_B_INV_04, VarbitID.PVPA_LOADOUT_B_INV_05, VarbitID.PVPA_LOADOUT_B_INV_06, VarbitID.PVPA_LOADOUT_B_INV_07,
            VarbitID.PVPA_LOADOUT_B_INV_08, VarbitID.PVPA_LOADOUT_B_INV_09, VarbitID.PVPA_LOADOUT_B_INV_10, VarbitID.PVPA_LOADOUT_B_INV_11,
            VarbitID.PVPA_LOADOUT_B_INV_12, VarbitID.PVPA_LOADOUT_B_INV_13, VarbitID.PVPA_LOADOUT_B_INV_14, VarbitID.PVPA_LOADOUT_B_INV_15,
            VarbitID.PVPA_LOADOUT_B_INV_16, VarbitID.PVPA_LOADOUT_B_INV_17, VarbitID.PVPA_LOADOUT_B_INV_18, VarbitID.PVPA_LOADOUT_B_INV_19,
            VarbitID.PVPA_LOADOUT_B_INV_20, VarbitID.PVPA_LOADOUT_B_INV_21, VarbitID.PVPA_LOADOUT_B_INV_22, VarbitID.PVPA_LOADOUT_B_INV_23,
            VarbitID.PVPA_LOADOUT_B_INV_24, VarbitID.PVPA_LOADOUT_B_INV_25, VarbitID.PVPA_LOADOUT_B_INV_26, VarbitID.PVPA_LOADOUT_B_INV_27,
            VarbitID.PVPA_LOADOUT_B_WORN_HAT, VarbitID.PVPA_LOADOUT_B_WORN_BACK, VarbitID.PVPA_LOADOUT_B_WORN_FRONT, VarbitID.PVPA_LOADOUT_B_WORN_RHAND,
            VarbitID.PVPA_LOADOUT_B_WORN_TORSO, VarbitID.PVPA_LOADOUT_B_WORN_LHAND, VarbitID.PVPA_LOADOUT_B_WORN_LEGS, VarbitID.PVPA_LOADOUT_B_WORN_HANDS,
            VarbitID.PVPA_LOADOUT_B_WORN_FEET, VarbitID.PVPA_LOADOUT_B_WORN_RING, VarbitID.PVPA_LOADOUT_B_WORN_QUIVER, VarbitID.PVPA_LOADOUT_B_SPELLBOOK},
        {VarbitID.PVPA_LOADOUT_C_INV_00, VarbitID.PVPA_LOADOUT_C_INV_01, VarbitID.PVPA_LOADOUT_C_INV_02, VarbitID.PVPA_LOADOUT_C_INV_03,
            VarbitID.PVPA_LOADOUT_C_INV_04, VarbitID.PVPA_LOADOUT_C_INV_05, VarbitID.PVPA_LOADOUT_C_INV_06, VarbitID.PVPA_LOADOUT_C_INV_07,
            VarbitID.PVPA_LOADOUT_C_INV_08, VarbitID.PVPA_LOADOUT_C_INV_09, VarbitID.PVPA_LOADOUT_C_INV_10, VarbitID.PVPA_LOADOUT_C_INV_11,
            VarbitID.PVPA_LOADOUT_C_INV_12, VarbitID.PVPA_LOADOUT_C_INV_13, VarbitID.PVPA_LOADOUT_C_INV_14, VarbitID.PVPA_LOADOUT_C_INV_15,
            VarbitID.PVPA_LOADOUT_C_INV_16, VarbitID.PVPA_LOADOUT_C_INV_17, VarbitID.PVPA_LOADOUT_C_INV_18, VarbitID.PVPA_LOADOUT_C_INV_19,
            VarbitID.PVPA_LOADOUT_C_INV_20, VarbitID.PVPA_LOADOUT_C_INV_21, VarbitID.PVPA_LOADOUT_C_INV_22, VarbitID.PVPA_LOADOUT_C_INV_23,
            VarbitID.PVPA_LOADOUT_C_INV_24, VarbitID.PVPA_LOADOUT_C_INV_25, VarbitID.PVPA_LOADOUT_C_INV_26, VarbitID.PVPA_LOADOUT_C_INV_27,
            VarbitID.PVPA_LOADOUT_C_WORN_HAT, VarbitID.PVPA_LOADOUT_C_WORN_BACK, VarbitID.PVPA_LOADOUT_C_WORN_FRONT, VarbitID.PVPA_LOADOUT_C_WORN_RHAND,
            VarbitID.PVPA_LOADOUT_C_WORN_TORSO, VarbitID.PVPA_LOADOUT_C_WORN_LHAND, VarbitID.PVPA_LOADOUT_C_WORN_LEGS, VarbitID.PVPA_LOADOUT_C_WORN_HANDS,
            VarbitID.PVPA_LOADOUT_C_WORN_FEET, VarbitID.PVPA_LOADOUT_C_WORN_RING, VarbitID.PVPA_LOADOUT_C_WORN_QUIVER, VarbitID.PVPA_LOADOUT_C_SPELLBOOK},
    };

    public static int buildIndex(String build)
    {
        for (int i = 0; i < BUILD_ORDER.length; i++) if (BUILD_ORDER[i].equals(build)) return i;
        return -1;
    }
}
