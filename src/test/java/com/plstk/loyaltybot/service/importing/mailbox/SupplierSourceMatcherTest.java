package com.plstk.loyaltybot.service.importing.mailbox;

import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplierSourceMatcherTest {

    private final SupplierSourceMatcher matcher = new SupplierSourceMatcher();

    private SupplierSource sourceWith(String senderAllowlist, String subjectPattern, String filenamePattern) {
        Supplier supplier = Supplier.builder().shopId("shop-a").name("Test supplier").build();
        return SupplierSource.builder()
                .shopId("shop-a")
                .supplier(supplier)
                .label("main")
                .senderAllowlist(senderAllowlist)
                .subjectPattern(subjectPattern)
                .filenamePattern(filenamePattern)
                .build();
    }

    @Test
    void isSupportedAttachmentType_acceptsXlsxAndXls_rejectsXlsmAndOthers() {
        assertTrue(matcher.isSupportedAttachmentType("price-25-06.xlsx"));
        assertTrue(matcher.isSupportedAttachmentType("price.XLS"));
        assertFalse(matcher.isSupportedAttachmentType("price.xlsm"));
        assertFalse(matcher.isSupportedAttachmentType("readme.docx"));
        assertFalse(matcher.isSupportedAttachmentType(null));
    }

    @Test
    void matches_exactEmailInAllowlist_matches() {
        SupplierSource source = sourceWith("price@supplier.ru", null, null);
        assertTrue(matcher.matches(source, "price@supplier.ru", "any subject", "price.xlsx"));
        assertFalse(matcher.matches(source, "other@supplier.ru", "any subject", "price.xlsx"));
    }

    @Test
    void matches_domainEntry_matchesAnySenderOnThatDomain() {
        SupplierSource source = sourceWith("supplier.ru", null, null);
        assertTrue(matcher.matches(source, "anyone@supplier.ru", "subject", "price.xlsx"));
        assertFalse(matcher.matches(source, "anyone@other.ru", "subject", "price.xlsx"));
    }

    @Test
    void matches_atPrefixedDomainEntry_matchesDomain() {
        SupplierSource source = sourceWith("@supplier.ru", null, null);
        assertTrue(matcher.matches(source, "price@supplier.ru", "subject", "price.xlsx"));
    }

    @Test
    void matches_multipleAllowlistEntries_separatedByNewlineOrComma() {
        SupplierSource source = sourceWith("a@one.ru,b@two.ru\nthree.ru", null, null);
        assertTrue(matcher.matches(source, "a@one.ru", "s", "f.xlsx"));
        assertTrue(matcher.matches(source, "b@two.ru", "s", "f.xlsx"));
        assertTrue(matcher.matches(source, "x@three.ru", "s", "f.xlsx"));
        assertFalse(matcher.matches(source, "x@four.ru", "s", "f.xlsx"));
    }

    @Test
    void matches_blankAllowlist_acceptsAnySender() {
        SupplierSource source = sourceWith("", null, null);
        assertTrue(matcher.matches(source, "anyone@anywhere.com", "s", "f.xlsx"));
    }

    @Test
    void matches_subjectPattern_isCaseInsensitiveRegexSearch() {
        SupplierSource source = sourceWith(null, "прайс", null);
        assertTrue(matcher.matches(source, "any@sender.ru", "Новый ПРАЙС на неделю", "f.xlsx"));
        assertFalse(matcher.matches(source, "any@sender.ru", "Каталог", "f.xlsx"));
    }

    @Test
    void matches_filenamePattern_isAdditionalRoutingFilter() {
        SupplierSource source = sourceWith(null, null, "^cosmetics-.*");
        assertTrue(matcher.matches(source, "s@x.ru", "subj", "cosmetics-price.xlsx"));
        assertFalse(matcher.matches(source, "s@x.ru", "subj", "perfume-price.xlsx"));
    }

    @Test
    void matches_invalidRegex_treatedAsNonMatchInsteadOfCrashing() {
        SupplierSource source = sourceWith(null, "[invalid(regex", null);
        assertFalse(matcher.matches(source, "s@x.ru", "subject", "f.xlsx"));
    }

    @Test
    void matchAll_returnsOnlySourcesThatMatch() {
        SupplierSource matchingSource = sourceWith("supplier.ru", null, null);
        SupplierSource otherSource = sourceWith("other.ru", null, null);

        var result = matcher.matchAll(java.util.List.of(matchingSource, otherSource), "x@supplier.ru", "s", "f.xlsx");

        assertTrue(result.contains(matchingSource));
        assertFalse(result.contains(otherSource));
    }
}
