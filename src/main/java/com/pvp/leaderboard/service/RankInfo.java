package com.pvp.leaderboard.service;

public class RankInfo
{
    public final String rank;
    public final int division;
    public final double progress;

    public RankInfo(String rank, int division, double progress)
    {
        this.rank = rank;
        this.division = division;
        this.progress = progress;
    }
}
