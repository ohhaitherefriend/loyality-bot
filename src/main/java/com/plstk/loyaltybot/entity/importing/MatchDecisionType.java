package com.plstk.loyaltybot.entity.importing;

public enum MatchDecisionType {
    EXACT,
    LEARNED,
    AI_MATCH,
    AI_NO_MATCH,
    /** Safe, fully-described row with no existing catalog candidate (Prompt 05); Product creation itself is Prompt 06's job. */
    NEW_PRODUCT,
    MANUAL,
    NO_MATCH
}
