package com.plstk.loyaltybot.service.importing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A row whose {@code column} raw value matches {@code matches} (plain Java regex, never SpEL/JS/SQL)
 * is skipped entirely (not counted as invalid) — e.g. category/subtotal rows in supplier price lists.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LayoutSkipRule {

    private String column;

    private String matches;
}
