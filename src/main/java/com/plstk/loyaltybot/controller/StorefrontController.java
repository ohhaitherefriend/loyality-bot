package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.commerce.CustomerOrder;
import com.plstk.loyaltybot.service.storefront.StorefrontService;
import com.plstk.loyaltybot.service.storefront.StorefrontService.StorefrontOrderRequest;
import com.plstk.loyaltybot.service.storefront.StorefrontService.StorefrontOrderResponse;
import com.plstk.loyaltybot.service.storefront.StorefrontService.StorefrontProductPageDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/storefront/{shopId}")
@RequiredArgsConstructor
public class StorefrontController {

    private static final String INIT_DATA_HEADER = "X-Telegram-Init-Data";

    private final StorefrontService storefrontService;

    @GetMapping("/settings")
    public ResponseEntity<?> getSettings(@PathVariable String shopId) {
        try {
            return ResponseEntity.ok(storefrontService.getSettings(shopId));
        } catch (StorefrontService.StorefrontNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (StorefrontService.StorefrontUnavailableException e) {
            return unavailable(e.getMessage());
        }
    }

    @GetMapping("/products")
    public ResponseEntity<?> listProducts(
            @PathVariable String shopId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String category) {

        try {
            StorefrontProductPageDto result = storefrontService.listProducts(
                    shopId, page, size, query, brand, category);
            return ResponseEntity.ok(result);
        } catch (StorefrontService.StorefrontNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (StorefrontService.StorefrontUnavailableException e) {
            return unavailable(e.getMessage());
        }
    }

    @GetMapping("/products/{productId}")
    public ResponseEntity<?> getProduct(
            @PathVariable String shopId,
            @PathVariable Long productId) {

        try {
            return ResponseEntity.ok(storefrontService.getProduct(shopId, productId));
        } catch (StorefrontService.StorefrontNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (StorefrontService.StorefrontUnavailableException e) {
            return unavailable(e.getMessage());
        }
    }

    @GetMapping("/brands")
    public ResponseEntity<?> listBrands(@PathVariable String shopId) {
        try {
            List<String> brands = storefrontService.listBrands(shopId);
            return ResponseEntity.ok(Map.of("brands", brands));
        } catch (StorefrontService.StorefrontNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (StorefrontService.StorefrontUnavailableException e) {
            return unavailable(e.getMessage());
        }
    }

    @PostMapping("/orders")
    public ResponseEntity<?> createOrder(
            @PathVariable String shopId,
            @RequestHeader(value = INIT_DATA_HEADER, required = false) String initData,
            @Valid @RequestBody StorefrontOrderBody body) {

        try {
            CustomerOrder order = storefrontService.createOrder(
                    shopId,
                    initData,
                    new StorefrontOrderRequest(
                            body.customerName(),
                            body.customerPhone(),
                            body.deliveryType(),
                            body.deliveryAddress(),
                            body.comment(),
                            body.items().stream()
                                    .map(i -> new StorefrontService.StorefrontOrderItemRequest(i.productId(), i.quantity()))
                                    .toList()));
            return ResponseEntity.status(HttpStatus.CREATED).body(StorefrontOrderResponse.from(order));
        } catch (StorefrontService.StorefrontAuthException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        } catch (StorefrontService.StorefrontNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (StorefrontService.StorefrontUnavailableException e) {
            return unavailable(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (com.plstk.loyaltybot.service.telegram.TelegramInitDataValidator.TelegramInitDataException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, String>> unavailable(String message) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", message));
    }

    public record StorefrontOrderBody(
            @NotEmpty String customerName,
            @NotEmpty String customerPhone,
            @NotNull com.plstk.loyaltybot.entity.commerce.DeliveryType deliveryType,
            String deliveryAddress,
            String comment,
            @NotEmpty List<StorefrontOrderItemBody> items
    ) {}

    public record StorefrontOrderItemBody(
            @NotNull Long productId,
            @NotNull Integer quantity
    ) {}
}
