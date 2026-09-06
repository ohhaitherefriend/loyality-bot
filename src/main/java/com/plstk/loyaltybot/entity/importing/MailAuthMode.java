package com.plstk.loyaltybot.entity.importing;

/**
 * Способ аутентификации почтового ящика. Реализация IMAP-клиента добавляется в Prompt 02.
 */
public enum MailAuthMode {
    OAUTH2,
    APP_PASSWORD
}
