package com.plstk.loyaltybot.service.importing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One target field's mapping to candidate source header names. {@code headerAliases} are matched
 * case-insensitively against actual header row cells, never against arbitrary regex/expressions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LayoutColumnMapping {

    private List<String> headerAliases;

    private LayoutColumnType type;

    @Builder.Default
    private boolean required = false;
}
