package com.plstk.loyaltybot.service.importing;

/**
 * Closed vocabulary of column value types a {@link LayoutRuleDefinition} may declare. Intentionally
 * small: there is no "expression"/"formula" type, so a rule can never carry arbitrary code.
 */
public enum LayoutColumnType {
    STRING,
    DECIMAL,
    INTEGER,
    BARCODE
}
