package com.plstk.loyaltybot.service.telegram;

import com.plstk.loyaltybot.config.CommerceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelegramInitDataValidatorTest {

    private TelegramInitDataValidator validator;

    @BeforeEach
    void setUp() {
        CommerceProperties properties = new CommerceProperties();
        properties.getMiniApp().setInitDataMaxAgeSeconds(86400);
        validator = new TelegramInitDataValidator(properties);
    }

    @Test
    void buildDataCheckString_sortsKeysAlphabetically() {
        Map<String, String> fields = Map.of(
                "user", "{\"id\":1}",
                "auth_date", "1662771648",
                "query_id", "AAHdF6IQAAAAAN0XohDhrOrc"
        );

        String result = TelegramInitDataValidator.buildDataCheckString(fields);

        assertEquals(
                "auth_date=1662771648\nquery_id=AAHdF6IQAAAAAN0XohDhrOrc\nuser={\"id\":1}",
                result
        );
    }

    @Test
    void validate_rejectsMissingHash() {
        String initData = "auth_date=1662771648&user=%7B%22id%22%3A1%7D";
        assertThrows(
                TelegramInitDataValidator.TelegramInitDataException.class,
                () -> validator.validate(initData, "123456:ABC-DEF")
        );
    }

    @Test
    void validate_rejectsInvalidSignature() {
        String initData = "auth_date=" + (System.currentTimeMillis() / 1000)
                + "&user=%7B%22id%22%3A1%7D&hash=deadbeef";
        assertThrows(
                TelegramInitDataValidator.TelegramInitDataException.class,
                () -> validator.validate(initData, "123456:ABC-DEF")
        );
    }
}
