package com.pvp.leaderboard.ui;

import com.pvp.leaderboard.queue.*;
import com.pvp.leaderboard.util.*;
import java.awt.*;
import java.time.*;
import java.util.List;
import javax.swing.*;

/** "Recently Queued", the time zone, then "Name - HH:mm" per join in the order given; hidden with no joins. */
class RecentQueued extends Ui.CapPanel
{
    RecentQueued()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(Ui.pad(12, 0, 0, 0));
        setOpaque(false);
        Ui.left(this);
        setVisible(false);
    }

    /** Shows {@code joins} in {@code clock}'s zone; an empty list hides the section. */
    void show(List<RecentJoin> joins, Clock clock)
    {
        removeAll();
        setVisible(!joins.isEmpty());
        if (!joins.isEmpty())
        {
            line(QueueText.RECENT, Font.BOLD, 16f, null);
            line(TimeText.zone(ZonedDateTime.now(clock)), Font.PLAIN, 13f, Ui.MUTED);
            for (RecentJoin j : joins) line(j.getName() + " - " + TimeText.hhmm(j.getAt(), clock.getZone()), Font.PLAIN, 14f, null);
        }
        revalidate();
        repaint();
    }

    /** A plain-text line: HTML is off before the text is set. */
    private void line(String text, int style, float pt, Color fg)
    {
        JLabel l = Ui.label(null, "", style, pt, fg);
        l.putClientProperty("html.disable", true);
        l.setText(text);
        add(l);
    }
}
