package com.plstk.loyaltybot.service.storefront;

import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.service.UserService;
import com.plstk.loyaltybot.service.telegram.TelegramInitDataValidator.ValidatedTelegramUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class StorefrontUserService {

    private final UserService userService;

    @Transactional
    public User findOrCreate(String shopId, ValidatedTelegramUser telegramUser, String customerPhone, String customerName) {
        if (!StringUtils.hasText(customerPhone)) {
            throw new IllegalArgumentException("Phone number is required");
        }

        String phone = normalizePhone(customerPhone);
        User user = userService.findByChatIdAndShopId(telegramUser.id(), shopId)
                .orElseGet(() -> User.builder()
                        .chatId(telegramUser.id())
                        .shopId(shopId)
                        .phoneNumber(phone)
                        .firstName(resolveFirstName(telegramUser, customerName))
                        .lastName(telegramUser.lastName())
                        .username(telegramUser.username())
                        .role(User.UserRole.USER)
                        .state(User.UserState.REGISTERED)
                        .build());

        user.setPhoneNumber(phone);
        if (StringUtils.hasText(customerName)) {
            user.setFirstName(customerName.trim());
        } else if (!StringUtils.hasText(user.getFirstName()) && StringUtils.hasText(telegramUser.firstName())) {
            user.setFirstName(telegramUser.firstName());
        }
        if (StringUtils.hasText(telegramUser.username())) {
            user.setUsername(telegramUser.username());
        }
        if (StringUtils.hasText(telegramUser.lastName())) {
            user.setLastName(telegramUser.lastName());
        }
        user.setState(User.UserState.REGISTERED);
        return userService.save(user);
    }

    private String resolveFirstName(ValidatedTelegramUser telegramUser, String customerName) {
        if (StringUtils.hasText(customerName)) {
            return customerName.trim();
        }
        return StringUtils.hasText(telegramUser.firstName()) ? telegramUser.firstName() : "Customer";
    }

    private String normalizePhone(String phone) {
        return phone.replaceAll("[^0-9+]", "");
    }
}
