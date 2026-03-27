package com.agentbot.polymarket;

import com.agentbot.polymarket.model.GammaMarket;
import com.agentbot.polymarket.model.LiveMarketState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Comparator;

@Slf4j
@Service
@RequiredArgsConstructor
public class PolymarketMarketDiscoveryService {

    private final PolymarketConfig config;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public List<GammaMarket> fetchActiveMarkets(int limit) {
        try {
            String url = config.getGammaApiBase() + "/markets?closed=false&active=true&limit=" + limit
                    + "&order=volume&ascending=false";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Gamma API returned status {}: {}", response.statusCode(),
                        response.body().substring(0, Math.min(200, response.body().length())));
                return List.of();
            }

            List<GammaMarket> markets = objectMapper.readValue(response.body(), new TypeReference<>() {});

            List<GammaMarket> filtered = markets.stream()
                    .filter(m -> m.isActive() && !m.isClosed())
                    .filter(m -> m.getClobTokenIds() != null && !m.getClobTokenIds().isBlank())
                    .filter(m -> m.isEnableOrderBook() || m.isAcceptingOrders())
                    .filter(m -> m.getLiquidityNum() > 500)
                    .sorted(Comparator.comparingDouble(GammaMarket::getLiquidityNum).reversed())
                    .collect(Collectors.toList());

            log.info("Discovered {} active markets from Polymarket (from {} total)", filtered.size(), markets.size());
            return filtered;

        } catch (Exception e) {
            log.error("Failed to fetch markets from Gamma API: {}", e.getMessage());
            return List.of();
        }
    }

    public Map<String, LiveMarketState> buildLiveMarketStates(List<GammaMarket> markets, int maxMarkets) {
        Map<String, LiveMarketState> states = new LinkedHashMap<>();
        int count = 0;

        for (GammaMarket market : markets) {
            if (count >= maxMarkets) break;

            List<String> tokenIds = parseJsonStringArray(market.getClobTokenIds());
            List<String> outcomes = parseJsonStringArray(market.getOutcomes());

            if (tokenIds.isEmpty()) continue;

            for (int i = 0; i < tokenIds.size(); i++) {
                String tokenId = tokenIds.get(i);
                String outcome = i < outcomes.size() ? outcomes.get(i) : "Unknown";

                LiveMarketState state = new LiveMarketState(
                        market.getConditionId() != null ? market.getConditionId() : market.getId(),
                        tokenId,
                        market.getQuestion(),
                        outcome
                );

                if (i == 0 && market.getBestBid() > 0) {
                    state.updateBbo(
                            BigDecimal.valueOf(market.getBestBid()),
                            BigDecimal.valueOf(market.getBestAsk())
                    );
                }

                states.put(tokenId, state);
            }
            count++;
        }

        log.info("Built {} live market states from {} markets", states.size(), count);
        return states;
    }

    public Map<String, BigDecimal[]> fetchOrderBooks(Collection<String> tokenIds) {
        Map<String, BigDecimal[]> result = new HashMap<>();
        for (String tokenId : tokenIds) {
            try {
                String url = config.getClobApiBase() + "/book?token_id=" + tokenId;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    var node = objectMapper.readTree(response.body());
                    BigDecimal bestBid = BigDecimal.ZERO;
                    BigDecimal bestAsk = BigDecimal.ONE;

                    var bids = node.get("bids");
                    if (bids != null && bids.isArray() && !bids.isEmpty()) {
                        bestBid = new BigDecimal(bids.get(bids.size() - 1).get("price").asText());
                    }
                    var asks = node.get("asks");
                    if (asks != null && asks.isArray() && !asks.isEmpty()) {
                        bestAsk = new BigDecimal(asks.get(asks.size() - 1).get("price").asText());
                    }

                    result.put(tokenId, new BigDecimal[]{bestBid, bestAsk});
                    log.debug("Book for {}: bid={} ask={}", tokenId.substring(0, 12), bestBid, bestAsk);
                } else {
                    log.debug("Book fetch failed for {}: status={}", tokenId.substring(0, 12), response.statusCode());
                }
            } catch (Exception e) {
                log.debug("Failed to fetch book for {}: {}", tokenId.substring(0, 12), e.getMessage());
            }
        }
        log.info("Fetched order books for {}/{} tokens", result.size(), tokenIds.size());
        return result;
    }

    private List<String> parseJsonStringArray(String jsonArrayStr) {
        if (jsonArrayStr == null || jsonArrayStr.isBlank()) return List.of();
        try {
            return objectMapper.readValue(jsonArrayStr, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("Failed to parse JSON array: {}", jsonArrayStr);
            return List.of();
        }
    }
}
