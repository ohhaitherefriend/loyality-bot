package com.plstk.loyaltybot.entity.commerce;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items", indexes = {
    @Index(name = "idx_order_items_order_id", columnList = "order_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(length = 255)
    private String skuSnapshot;

    @Column(length = 255)
    private String barcodeSnapshot;

    @Column(length = 255)
    private String brandSnapshot;

    @Column(length = 1024)
    private String nameSnapshot;

    @Column(precision = 19, scale = 2)
    private BigDecimal priceSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private AvailabilityMode availabilityModeSnapshot;

    @Column(nullable = false)
    private Integer quantity;

    @Column(precision = 19, scale = 2)
    private BigDecimal lineTotal;
}
