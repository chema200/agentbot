package com.agentbot.polymarket;

import com.agentbot.polymarket.model.LiveMarketState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class PolymarketMarketDataClient extends WebSocketClient {

    private final ObjectMapper objectMapper;
    private final Map<String, LiveMarketState> marketStates;
    private final List<String> assetIds;
    private volatile boolean subscribed = false;

    public PolymarketMarketDataClient(URI serverUri, ObjectMapper objectMapper,
                                       Map<String, LiveMarketState> marketStates,
                                       List<String> assetIds) {
        super(serverUri);
        this.objectMapper = objectMapper;
        this.marketStates = marketStates;
        this.assetIds = assetIds;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("WebSocket connected to Polymarket");
        sendSubscription();
    }

    private void sendSubscription() {
        try {
            // Polymarket CLOB WS subscription format
            Map<String, Object> sub = Map.of(
                    "type", "subscribe",
                    "channel", "market",
                    "assets_ids", assetIds
            );
            String msg = objectMapper.writeValueAsString(sub);
            send(msg);
            subscribed = true;
            log.info("Subscribed to {} asset IDs", assetIds.size());
        } catch (Exception e) {
            log.error("Failed to send subscription: {}", e.getMessage());
        }
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);

            // Handle different message types from Polymarket WS
            String type = root.has("event_type") ? root.get("event_type").asText() : "";

            if ("book".equals(type) || "price_change".equals(type)) {
                handleBookUpdate(root);
            } else if ("trade".equals(type) || "last_trade_price".equals(type)) {
                handleTradeUpdate(root);
            } else if (root.has("market") || root.has("asset_id")) {
                handleGenericUpdate(root);
            }
        } catch (Exception e) {
            log.debug("Failed to parse WS message: {}", e.getMessage());
        }
    }

    private void handleBookUpdate(JsonNode root) {
        String assetId = extractAssetId(root);
        if (assetId == null) return;

        LiveMarketState state = marketStates.get(assetId);
        if (state == null) return;

        BigDecimal bid = extractPrice(root, "best_bid_price", "bid");
        BigDecimal ask = extractPrice(root, "best_ask_price", "ask");

        if (bid != null || ask != null) {
            state.updateBbo(bid, ask);
        }
    }

    private void handleTradeUpdate(JsonNode root) {
        String assetId = extractAssetId(root);
        if (assetId == null) return;

        LiveMarketState state = marketStates.get(assetId);
        if (state == null) return;

        BigDecimal price = extractPrice(root, "price", "last_trade_price");
        BigDecimal size = extractPrice(root, "size", "amount");

        if (price != null) {
            state.recordTrade(price, size != null ? size : BigDecimal.ZERO);
        }
    }

    private void handleGenericUpdate(JsonNode root) {
        // Attempt to extract BBO from any message format
        String assetId = extractAssetId(root);
        if (assetId == null) return;

        LiveMarketState state = marketStates.get(assetId);
        if (state == null) return;

        // Try multiple field name patterns
        BigDecimal bid = extractPrice(root, "best_bid_price", "bid_price");
        BigDecimal ask = extractPrice(root, "best_ask_price", "ask_price");
        BigDecimal price = extractPrice(root, "price", "last_trade_price");

        if (bid != null || ask != null) {
            state.updateBbo(bid, ask);
        }
        if (price != null && (bid == null && ask == null)) {
            state.recordTrade(price, BigDecimal.ZERO);
        }
    }

    private String extractAssetId(JsonNode root) {
        if (root.has("asset_id")) return root.get("asset_id").asText();
        if (root.has("market")) return root.get("market").asText();
        if (root.has("token_id")) return root.get("token_id").asText();
        return null;
    }

    private BigDecimal extractPrice(JsonNode root, String... fieldNames) {
        for (String field : fieldNames) {
            if (root.has(field)) {
                try {
                    String val = root.get(field).asText();
                    if (val != null && !val.isBlank() && !"null".equals(val)) {
                        return new BigDecimal(val);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        subscribed = false;
        log.warn("WebSocket closed: code={} reason={} remote={}", code, reason, remote);
    }

    @Override
    public void onError(Exception ex) {
        log.error("WebSocket error: {}", ex.getMessage());
    }

    public boolean isSubscribed() {
        return subscribed && isOpen();
    }
}
