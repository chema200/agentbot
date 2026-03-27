package com.agentbot.shadow;

/**
 * Rolling-quality classification for shadow market guard.
 * Order of severity for cooldown decisions: TOXIC &gt; HIGH_CHURN &gt; LOSER &gt; NEUTRAL/WINNER.
 */
public enum MarketGuardClassification {
    /** Positive rolling PnL, no recent toxicity, enough fills — protect from heavy penalty. */
    WINNER,
    /** Default / insufficient data / mixed signals. */
    NEUTRAL,
    /** Sustained negative rolling PnL or weak flow. */
    LOSER,
    /** Elevated toxic fill rate in rolling window. */
    TOXIC,
    /** Very high stale-cancel churn vs few fills. */
    HIGH_CHURN
}
