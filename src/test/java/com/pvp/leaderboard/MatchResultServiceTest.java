package com.pvp.leaderboard;

import org.junit.Test;
import org.junit.Ignore;

public class MatchResultServiceTest
{
    // Match submission is HMAC-signed only (the Bearer/idToken path was
    // removed with the Cognito → Discord migration). Network-level coverage
    // is disabled in the unit context pending injected-client refactor.
    @Ignore("Network/API tests disabled in unit context; migration to injected clients")
    @Test
    public void testSignedMatchSubmission() throws Exception {}
}