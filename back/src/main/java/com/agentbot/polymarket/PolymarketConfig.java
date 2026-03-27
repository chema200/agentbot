package com.agentbot.polymarket;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "polymarket")
public class PolymarketConfig {
    private String gammaApiBase = "https://gamma-api.polymarket.com";
    private String clobApiBase = "https://clob.polymarket.com";
    private String clobWsUrl = "wss://ws-subscriptions-clob.polymarket.com/ws/market";
}
