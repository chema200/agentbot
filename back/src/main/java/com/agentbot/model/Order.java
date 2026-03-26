package com.agentbot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String market;

    @Enumerated(EnumType.STRING)
    private Side side;

    private BigDecimal price;
    private BigDecimal size;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private Instant createdAt;

    public enum Side { BUY, SELL }
    public enum OrderStatus { OPEN, CANCELLED, FILLED }
}
