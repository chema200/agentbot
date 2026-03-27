package com.agentbot.polymarket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GammaMarket {
    private String id;
    private String question;
    private String description;
    private boolean active;
    private boolean closed;

    @JsonProperty("endDateIso")
    private String endDateIso;

    private String slug;

    @JsonProperty("conditionId")
    private String conditionId;

    private String outcomes;

    @JsonProperty("clobTokenIds")
    private String clobTokenIds;

    private String volume;
    private String liquidity;

    @JsonProperty("enableOrderBook")
    private boolean enableOrderBook;

    @JsonProperty("acceptingOrders")
    private boolean acceptingOrders;

    private double spread;
    private double bestBid;
    private double bestAsk;
    private double lastTradePrice;

    @JsonProperty("volumeNum")
    private double volumeNum;

    @JsonProperty("liquidityNum")
    private double liquidityNum;
}
