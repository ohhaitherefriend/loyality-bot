package com.plstk.loyaltybot.service.importing.mailbox;

import com.plstk.loyaltybot.entity.importing.SupplierSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Routes one incoming message/attachment to the {@link SupplierSource}(s) whose filters accept
 * it. Several sources may match the same mailbox (shared inbox for several suppliers); if more
 * than one matches, the attachment is ingested into every matching source independently.
 */
@Component
public class SupplierSourceMatcher {

    /**
     * Upper bound on a single regex match's wall-clock time. {@code subjectPattern}/
     * {@code filenamePattern} are admin-configured but not validated for catastrophic
     * backtracking, and are matched against sender-controlled subject/filename text (the sender
     * allowlist is a separate, independently-applied filter - a wildcard/domain-wide allowlist
     * still lets any sender on that domain reach this match). This runs on the shared, small
     * {@code spring.task.scheduling.pool.size} pool used by every {@code @Scheduled} job in the
     * app, so a single pathological pattern must never be allowed to hang one of those threads
     * indefinitely and starve unrelated loyalty cron jobs.
     */
    private static final long REGEX_MATCH_BUDGET_NANOS = 200_000_000L;

    /** Only .xlsx/.xls are accepted at the pipeline level; .xlsm and everything else is rejected. */
    private static final Pattern ALLOWED_EXTENSION = Pattern.compile("(?i)^.*\\.(xlsx|xls)$");

    public boolean isSupportedAttachmentType(String filename) {
        return filename != null && ALLOWED_EXTENSION.matcher(filename.trim()).matches();
    }

    public boolean matches(SupplierSource source, String fromAddress, String subject, String filename) {
        return matchesSender(source.getSenderAllowlist(), fromAddress)
                && matchesRegex(source.getSubjectPattern(), subject)
                && matchesRegex(source.getFilenamePattern(), filename);
    }

    public List<SupplierSource> matchAll(List<SupplierSource> candidates, String fromAddress, String subject, String filename) {
        return candidates.stream()
                .filter(source -> matches(source, fromAddress, subject, filename))
                .toList();
    }

    private boolean matchesSender(String senderAllowlist, String fromAddress) {
        if (senderAllowlist == null || senderAllowlist.isBlank()) {
            // No allowlist configured: source accepts any sender on its mailbox. Operators are
            // expected to always set this in production; left permissive here so a freshly
            // created source is not silently useless before it's configured.
            return true;
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            return false;
        }
        String normalizedFrom = fromAddress.trim().toLowerCase();
        String domain = extractDomain(normalizedFrom);

        for (String rawEntry : senderAllowlist.split("[\\r\\n,;]+")) {
            String entry = rawEntry.trim().toLowerCase();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.startsWith("@")) {
                if (domain != null && domain.equals(entry.substring(1))) {
                    return true;
                }
            } else if (entry.contains("@")) {
                if (entry.equals(normalizedFrom)) {
                    return true;
                }
            } else {
                // Bare domain, e.g. "supplier.ru".
                if (domain != null && domain.equals(entry)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String extractDomain(String emailAddress) {
        int at = emailAddress.indexOf('@');
        return at >= 0 && at < emailAddress.length() - 1 ? emailAddress.substring(at + 1) : null;
    }

    private boolean matchesRegex(String pattern, String value) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        try {
            // UNICODE_CASE is required alongside CASE_INSENSITIVE for correct case folding of
            // non-ASCII subjects (e.g. Cyrillic "прайс"/"ПРАЙС"), which suppliers commonly use.
            int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            CharSequence bounded = new TimeBoundedCharSequence(value, REGEX_MATCH_BUDGET_NANOS);
            return Pattern.compile(pattern, flags).matcher(bounded).find();
        } catch (PatternSyntaxException e) {
            // An invalid regex must never silently match everything or crash the polling job;
            // treat it as "does not match" so the source simply stays inactive until fixed.
            return false;
        } catch (RegexTimeoutException e) {
            // Catastrophic backtracking (or just a pathological pattern on long input) - fail
            // closed exactly like a syntax error above, rather than hanging the shared scheduler
            // thread pool indefinitely.
            return false;
        }
    }

    /**
     * Wraps a {@link CharSequence} with a wall-clock deadline checked on every {@code charAt}
     * call. The regex engine calls {@code charAt} on every character it examines, including every
     * backtracking step, so this bounds total match time without needing a separate thread/
     * {@code Future} (which could only stop the *caller* from waiting, not the runaway match
     * itself still burning a thread forever) - it aborts the match from the inside.
     */
    private static final class TimeBoundedCharSequence implements CharSequence {
        private final CharSequence delegate;
        private final long deadlineNanos;

        TimeBoundedCharSequence(CharSequence delegate, long budgetNanos) {
            this.delegate = delegate;
            this.deadlineNanos = System.nanoTime() + budgetNanos;
        }

        private TimeBoundedCharSequence(CharSequence delegate, long deadlineNanos, boolean ignored) {
            this.delegate = delegate;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadlineNanos) {
                throw new RegexTimeoutException();
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new TimeBoundedCharSequence(delegate.subSequence(start, end), deadlineNanos, true);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    private static final class RegexTimeoutException extends RuntimeException {
        RegexTimeoutException() {
            super(null, null, false, false);
        }
    }
}
