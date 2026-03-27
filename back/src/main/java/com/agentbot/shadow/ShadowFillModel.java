package com.agentbot.shadow;

import com.agentbot.polymarket.model.LiveMarketState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Slf4j
@Component
public class ShadowFillModel {

    private static final BigDecimal FEE_RATE = new BigDecimal("0.002");
    /** Fraction of initial gap (creation BBO → our limit) that must close to count as “moved toward” fill. */
    private static final BigDecimal TOWARD_FILL_FRACTION = new BigDecimal("0.5");

    public Optional<ShadowFill> evaluateFill(ShadowOrder order, LiveMarketState liveState) {
        String marketShort = shortMarket(order.getTokenId());
        BigDecimal liveBid = liveState.getBestBid().get();
        BigDecimal liveAsk = liveState.getBestAsk().get();
        BigDecimal liveMid = liveState.getMidPrice();
        BigDecimal spread = liveState.getSpread();

        if (!order.isActive()) {
            logFillCheck(order, marketShort, liveBid, liveAsk, liveMid, spread, false, false,
                    "not_active");
            return Optional.empty();
        }

        if (liveBid.compareTo(BigDecimal.ZERO) <= 0 || liveAsk.compareTo(BigDecimal.ZERO) <= 0) {
            logFillCheck(order, marketShort, liveBid, liveAsk, liveMid, spread, false, false,
                    "invalid_bbo");
            return Optional.empty();
        }

        String side = order.getSide();
        BigDecimal p = order.getPrice();
        boolean crossed;
        boolean wouldFill;
        String reason;

        if ("BUY".equals(side)) {
            crossed = liveAsk.compareTo(p) <= 0;
            wouldFill = crossed
                    || liveBid.compareTo(p) >= 0
                    || liveMid.compareTo(p) <= 0
                    || buyAskWalkedTowardFromCreation(order, liveAsk, liveMid, liveBid);
            reason = buyFillReason(crossed, wouldFill, order, liveBid, liveAsk, liveMid);
        } else {
            crossed = liveBid.compareTo(p) >= 0;
            wouldFill = crossed
                    || liveMid.compareTo(p) >= 0
                    || sellBidWalkedTowardFromCreation(order, liveBid, liveMid, liveAsk);
            reason = sellFillReason(crossed, wouldFill, order, liveBid, liveAsk, liveMid);
        }

        logFillCheck(order, marketShort, liveBid, liveAsk, liveMid, spread, crossed, wouldFill, reason);

        if (!wouldFill) {
            return Optional.empty();
        }

        BigDecimal fillPrice = order.getPrice();
        BigDecimal fillSize = order.getSize();
        BigDecimal fee = fillPrice.multiply(fillSize).multiply(FEE_RATE).setScale(6, RoundingMode.HALF_UP);

        BigDecimal slippage;
        if ("BUY".equals(side)) {
            slippage = fillPrice.subtract(liveMid);
        } else {
            slippage = liveMid.subtract(fillPrice);
        }

        BigDecimal lastTrade = liveState.getLastTradePrice().get();
        boolean toxic = false;
        if (lastTrade.compareTo(BigDecimal.ZERO) > 0) {
            if ("BUY".equals(side) && lastTrade.compareTo(fillPrice) < 0) {
                toxic = true;
            } else if ("SELL".equals(side) && lastTrade.compareTo(fillPrice) > 0) {
                toxic = true;
            }
        }

        BigDecimal estimatedPnl;
        if ("BUY".equals(side)) {
            estimatedPnl = liveMid.subtract(fillPrice).multiply(fillSize).subtract(fee);
        } else {
            estimatedPnl = fillPrice.subtract(liveMid).multiply(fillSize).subtract(fee);
        }

        ShadowFill fill = ShadowFill.builder()
                .fillId(UUID.randomUUID().toString().substring(0, 8))
                .orderId(order.getOrderId())
                .tokenId(order.getTokenId())
                .marketQuestion(order.getMarketQuestion())
                .outcome(order.getOutcome())
                .side(order.getSide())
                .fillPrice(fillPrice)
                .fillSize(fillSize)
                .fee(fee)
                .slippage(slippage.setScale(6, RoundingMode.HALF_UP))
                .midAtFill(liveMid)
                .liveBidAtFill(liveBid)
                .liveAskAtFill(liveAsk)
                .wouldHaveBeenToxic(toxic)
                .filledAt(Instant.now())
                .estimatedPnl(estimatedPnl.setScale(6, RoundingMode.HALF_UP))
                .build();

        log.info("[SHADOW_FILL]\norder_id={}\nmarket={}\nside={}\nprice={}\nsize={}\nfee={}\nslippage={}\npnl={}\ntoxic={}",
                fill.getOrderId(),
                marketShort,
                fill.getSide(),
                fill.getFillPrice(),
                fill.getFillSize(),
                fill.getFee(),
                fill.getSlippage(),
                fill.getEstimatedPnl(),
                fill.isWouldHaveBeenToxic());

        return Optional.of(fill);
    }

    /**
     * BUY: creation ask was above our bid; fill if ask has fallen and mid/bid moved favorably so at least
     * half the initial (ask − limit) gap has closed, or BBO vs creation shows movement toward our price
     * together with that progress.
     */
    private static boolean buyAskWalkedTowardFromCreation(ShadowOrder order, BigDecimal liveAsk,
                                                          BigDecimal liveMid, BigDecimal liveBid) {
        BigDecimal p = order.getPrice();
        BigDecimal a0 = order.getLiveBestAsk();
        BigDecimal m0 = order.getLiveMid();
        BigDecimal b0 = order.getLiveBestBid();
        if (a0 == null) {
            return false;
        }
        BigDecimal gap0 = a0.subtract(p);
        if (gap0.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (liveAsk.compareTo(p) <= 0) {
            return false;
        }
        BigDecimal gapNow = liveAsk.subtract(p);
        BigDecimal closed = gap0.subtract(gapNow);
        boolean halfGap = closed.compareTo(gap0.multiply(TOWARD_FILL_FRACTION)) >= 0;
        boolean askDown = liveAsk.compareTo(a0) < 0;
        if (!askDown || !halfGap) {
            return false;
        }
        boolean midMovedToward = m0 == null || liveMid.compareTo(m0) < 0;
        boolean bidMovedToward = b0 == null || liveBid.compareTo(b0) > 0;
        return midMovedToward || bidMovedToward;
    }

    /**
     * SELL: creation bid was below our ask; fill if bid has lifted so at least half the initial (limit − bid)
     * gap has closed, with favorable movement vs creation BBO or mid.
     */
    private static boolean sellBidWalkedTowardFromCreation(ShadowOrder order, BigDecimal liveBid,
                                                           BigDecimal liveMid, BigDecimal liveAsk) {
        BigDecimal p = order.getPrice();
        BigDecimal b0 = order.getLiveBestBid();
        BigDecimal m0 = order.getLiveMid();
        BigDecimal a0 = order.getLiveBestAsk();
        if (b0 == null) {
            return false;
        }
        BigDecimal gap0 = p.subtract(b0);
        if (gap0.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (liveBid.compareTo(p) >= 0) {
            return false;
        }
        BigDecimal closed = liveBid.subtract(b0);
        boolean halfGap = closed.compareTo(gap0.multiply(TOWARD_FILL_FRACTION)) >= 0;
        boolean bidUp = liveBid.compareTo(b0) > 0;
        if (!bidUp || !halfGap) {
            return false;
        }
        boolean midMovedToward = m0 == null || liveMid.compareTo(m0) > 0;
        boolean askMovedToward = a0 == null || liveAsk.compareTo(a0) < 0;
        return midMovedToward || askMovedToward;
    }

    private static String buyFillReason(boolean crossed, boolean wouldFill, ShadowOrder order,
                                        BigDecimal liveBid, BigDecimal liveAsk, BigDecimal liveMid) {
        if (!wouldFill) {
            return "no_touch_no_bid_at_quote_mid_above_quote_insufficient_ask_move_from_creation";
        }
        BigDecimal p = order.getPrice();
        if (crossed) {
            return "ask_crossed_limit";
        }
        if (liveBid.compareTo(p) >= 0) {
            return "bid_reached_quote";
        }
        if (liveMid.compareTo(p) <= 0) {
            return "mid_through_buy";
        }
        if (buyAskWalkedTowardFromCreation(order, liveAsk, liveMid, liveBid)) {
            return "ask_walked_half_gap_from_creation_bbo";
        }
        return "filled";
    }

    private static String sellFillReason(boolean crossed, boolean wouldFill, ShadowOrder order,
                                         BigDecimal liveBid, BigDecimal liveAsk, BigDecimal liveMid) {
        if (!wouldFill) {
            return "no_touch_mid_below_quote_insufficient_bid_move_from_creation";
        }
        BigDecimal p = order.getPrice();
        if (crossed) {
            return "bid_crossed_limit";
        }
        if (liveMid.compareTo(p) >= 0) {
            return "mid_through_sell";
        }
        if (sellBidWalkedTowardFromCreation(order, liveBid, liveMid, liveAsk)) {
            return "bid_walked_half_gap_from_creation_bbo";
        }
        return "filled";
    }

    private void logFillCheck(ShadowOrder order, String marketShort,
                              BigDecimal liveBid, BigDecimal liveAsk, BigDecimal liveMid,
                              BigDecimal spread, boolean crossed, boolean wouldFill, String reason) {
        log.debug("[SHADOW_FILL_CHECK]\norder_id={}\nmarket={}\nside={}\norder_price={}\nbest_bid={}\nbest_ask={}\nmid={}\nspread={}\ncrossed={}\nwould_fill={}\nreason={}",
                order.getOrderId(),
                marketShort,
                order.getSide(),
                order.getPrice(),
                liveBid,
                liveAsk,
                liveMid,
                spread,
                crossed,
                wouldFill,
                reason);
    }

    public ShadowOrder createHypotheticalOrder(String tokenId, String question, String outcome,
                                                 String side, BigDecimal price, BigDecimal size,
                                                 LiveMarketState liveState,
                                                 BigDecimal edgeScore, BigDecimal capitalShare) {
        BigDecimal liveBid = liveState.getBestBid().get();
        BigDecimal liveAsk = liveState.getBestAsk().get();
        BigDecimal liveMid = liveState.getMidPrice();
        String regime = liveState.getRegime().name();

        ShadowOrder order = ShadowOrder.builder()
                .orderId(UUID.randomUUID().toString().substring(0, 8))
                .tokenId(tokenId)
                .marketQuestion(question)
                .outcome(outcome)
                .side(side)
                .price(price.setScale(4, RoundingMode.HALF_UP))
                .size(size.setScale(0, RoundingMode.HALF_UP))
                .status("OPEN")
                .createdAt(Instant.now())
                .liveBestBid(liveBid)
                .liveBestAsk(liveAsk)
                .liveMid(liveMid)
                .edgeScore(edgeScore)
                .capitalShare(capitalShare)
                .regime(regime)
                .build();

        log.info("[SHADOW_ORDER]\norder_id={}\nmarket={}\nside={}\nprice={}\nsize={}\nbid={}\nask={}\nmid={}\nregime={}",
                order.getOrderId(),
                shortMarket(tokenId),
                order.getSide(),
                order.getPrice(),
                order.getSize(),
                liveBid,
                liveAsk,
                liveMid,
                regime);

        return order;
    }

    private static String shortMarket(String tokenId) {
        if (tokenId == null) {
            return "";
        }
        return tokenId.length() > 16 ? tokenId.substring(0, 16) : tokenId;
    }
}
