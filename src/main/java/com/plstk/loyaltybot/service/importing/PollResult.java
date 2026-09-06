package com.plstk.loyaltybot.service.importing;

public record PollResult(String status, int ingestedCount, int skippedCount, String message) {

    public static PollResult success(int ingestedCount, int skippedCount) {
        return new PollResult("SUCCESS", ingestedCount, skippedCount, null);
    }

    public static PollResult failure(String message) {
        return new PollResult("FAILURE", 0, 0, message);
    }

    public static PollResult skippedAlreadyClaimed() {
        return new PollResult("SKIPPED_ALREADY_CLAIMED", 0, 0, "Poll already in progress on another replica");
    }

    public static PollResult skippedDisabledOrMissing() {
        return new PollResult("SKIPPED_DISABLED", 0, 0, "Mailbox disabled or not found");
    }
}
