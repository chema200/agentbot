package com.agentbot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "fills")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Fill {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String market;

    @Enumerated(EnumType.STRING)
    private Order.Side side;

    private BigDecimal price;
    private BigDecimal size;
    private BigDecimal fee;
    private Instant filledAt;
}
