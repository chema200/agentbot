package com.agentbot.service;

import com.agentbot.engine.BacktestRunner;
import com.agentbot.engine.StressProfile;
import com.agentbot.model.*;
import com.agentbot.repository.BacktestRunRepository;
import com.agentbot.repository.MonteCarloRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private final BacktestRunner runner;
    private final BacktestRunRepository backtestRepo;
    private final MonteCarloRunRepository mcRepo;

    public BacktestResultDto runSingleBacktest(int cycles, long seed, String profileName) {
        StressProfile profile = parseProfile(profileName);
        log.info("Starting backtest: cycles={} seed={} profile={}", cycles, seed, profile);

        BacktestResultDto result = runner.run(cycles, seed, profile);
        persistRun(result);

        log.info("Backtest complete: runId={} pnl={} fills={} elapsed={}ms",
                result.getRunId(), result.getTotalPnl(), result.getTotalFills(), result.getElapsedMs());
        return result;
    }

    public MonteCarloResultDto runMonteCarlo(int cyclesPerRun, int numSeeds, String profileName) {
        StressProfile profile = parseProfile(profileName);
        long startMs = System.currentTimeMillis();
        String mcRunId = "mc-" + UUID.randomUUID().toString().substring(0, 10);

        log.info("Starting Monte Carlo: seeds={} cycles={} profile={}", numSeeds, cyclesPerRun, profile);

        List<BacktestResultDto> runs = new ArrayList<>();
        Random seedGen = new Random();

        for (int i = 0; i < numSeeds; i++) {
            long seed = seedGen.nextLong();
            BacktestResultDto result = runner.run(cyclesPerRun, seed, profile);
            persistRun(result);
            runs.add(result);

            if ((i + 1) % 10 == 0) {
                log.info("  MC progress: {}/{} seeds completed", i + 1, numSeeds);
            }
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        MonteCarloResultDto mcResult = aggregateMonteCarloResults(mcRunId, runs, profile, cyclesPerRun, elapsedMs);
        persistMcRun(mcResult);

        log.info("Monte Carlo complete: mcRunId={} avgPnl={} winRate={} sharpe={} elapsed={}ms",
                mcRunId, mcResult.getAvgPnl(), mcResult.getWinRate(),
                mcResult.getSharpeRatio(), mcResult.getElapsedMs());
        return mcResult;
    }

    public List<BacktestResultDto> getRecentRuns() {
        return backtestRepo.findTop50ByOrderByCreatedAtDesc().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public List<BacktestResultDto> getRunsByProfile(String profile) {
        return backtestRepo.findByStressProfileOrderByCreatedAtDesc(profile).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public List<MonteCarloResultDto> getRecentMcRuns() {
        return mcRepo.findTop20ByOrderByCreatedAtDesc().stream()
                .map(this::mcToDto)
                .collect(Collectors.toList());
    }

    public Map<String, Object> getComparisonAcrossProfiles() {
        Map<String, Object> comparison = new LinkedHashMap<>();
        for (StressProfile sp : StressProfile.values()) {
            List<BacktestRunEntity> runs = backtestRepo.findByStressProfileOrderByCreatedAtDesc(sp.name());
            if (runs.isEmpty()) continue;

            DoubleSummaryStatistics stats = runs.stream()
                    .mapToDouble(r -> r.getTotalPnl().doubleValue())
                    .summaryStatistics();

            double winRate = runs.stream()
                    .filter(r -> r.getTotalPnl().compareTo(BigDecimal.ZERO) > 0)
                    .count() / (double) runs.size();

            Map<String, Object> profileStats = new LinkedHashMap<>();
            profileStats.put("count", runs.size());
            profileStats.put("avgPnl", BigDecimal.valueOf(stats.getAverage()).setScale(4, RoundingMode.HALF_UP));
            profileStats.put("minPnl", BigDecimal.valueOf(stats.getMin()).setScale(4, RoundingMode.HALF_UP));
            profileStats.put("maxPnl", BigDecimal.valueOf(stats.getMax()).setScale(4, RoundingMode.HALF_UP));
            profileStats.put("winRate", BigDecimal.valueOf(winRate).setScale(4, RoundingMode.HALF_UP));
            comparison.put(sp.name(), profileStats);
        }
        return comparison;
    }

    private MonteCarloResultDto aggregateMonteCarloResults(String mcRunId, List<BacktestResultDto> runs,
                                                           StressProfile profile, int cyclesPerRun, long elapsedMs) {
        List<Double> pnls = runs.stream().map(r -> r.getTotalPnl().doubleValue()).sorted().toList();
        int n = pnls.size();

        double avg = pnls.stream().mapToDouble(d -> d).average().orElse(0.0);
        double median = n % 2 == 0
                ? (pnls.get(n / 2 - 1) + pnls.get(n / 2)) / 2.0
                : pnls.get(n / 2);
        double variance = pnls.stream().mapToDouble(d -> (d - avg) * (d - avg)).average().orElse(0.0);
        double std = Math.sqrt(variance);
        double min = pnls.stream().mapToDouble(d -> d).min().orElse(0.0);
        double max = pnls.stream().mapToDouble(d -> d).max().orElse(0.0);
        double winRate = (double) pnls.stream().filter(p -> p > 0).count() / n;
        double sharpe = std > 0 ? avg / std : 0.0;

        double avgFills = runs.stream().mapToInt(BacktestResultDto::getTotalFills).average().orElse(0.0);
        double avgToxic = runs.stream().mapToInt(BacktestResultDto::getToxicFills).average().orElse(0.0);
        double avgDD = runs.stream().mapToDouble(r -> r.getMaxDrawdown().doubleValue()).average().orElse(0.0);
        double maxDD = runs.stream().mapToDouble(r -> r.getMaxDrawdown().doubleValue()).max().orElse(0.0);

        return MonteCarloResultDto.builder()
                .mcRunId(mcRunId)
                .numSeeds(n)
                .stressProfile(profile.name())
                .cyclesPerRun(cyclesPerRun)
                .avgPnl(bd(avg))
                .medianPnl(bd(median))
                .stdPnl(bd(std))
                .minPnl(bd(min))
                .maxPnl(bd(max))
                .winRate(bd(winRate))
                .avgDrawdown(bd(avgDD))
                .maxDrawdown(bd(maxDD))
                .avgFills(bd(avgFills))
                .avgToxicFills(bd(avgToxic))
                .sharpeRatio(bd(sharpe))
                .elapsedMs(elapsedMs)
                .createdAt(Instant.now())
                .individualRuns(runs)
                .build();
    }

    private void persistRun(BacktestResultDto r) {
        backtestRepo.save(BacktestRunEntity.builder()
                .runId(r.getRunId())
                .seed(r.getSeed())
                .stressProfile(r.getStressProfile())
                .cycles(r.getCycles())
                .simulatedDurationSec(r.getSimulatedDurationSec())
                .totalPnl(r.getTotalPnl())
                .tradingPnl(r.getTradingPnl())
                .rewardPnl(r.getRewardPnl())
                .totalFills(r.getTotalFills())
                .toxicFills(r.getToxicFills())
                .totalFees(r.getTotalFees())
                .maxExposure(r.getMaxExposure())
                .maxDrawdown(r.getMaxDrawdown())
                .finalInventoryNet(r.getFinalInventoryNet())
                .avgProfitPerFill(r.getAvgProfitPerFill())
                .adverseSelectionRate(r.getAdverseSelectionRate())
                .winRate(r.getWinRate())
                .activeMarkets(r.getActiveMarkets())
                .elapsedMs(r.getElapsedMs())
                .createdAt(Instant.now())
                .build());
    }

    private void persistMcRun(MonteCarloResultDto r) {
        mcRepo.save(MonteCarloRunEntity.builder()
                .mcRunId(r.getMcRunId())
                .numSeeds(r.getNumSeeds())
                .stressProfile(r.getStressProfile())
                .cyclesPerRun(r.getCyclesPerRun())
                .avgPnl(r.getAvgPnl())
                .medianPnl(r.getMedianPnl())
                .stdPnl(r.getStdPnl())
                .minPnl(r.getMinPnl())
                .maxPnl(r.getMaxPnl())
                .winRate(r.getWinRate())
                .avgDrawdown(r.getAvgDrawdown())
                .maxDrawdown(r.getMaxDrawdown())
                .avgFills(r.getAvgFills())
                .avgToxicFills(r.getAvgToxicFills())
                .sharpeRatio(r.getSharpeRatio())
                .elapsedMs(r.getElapsedMs())
                .createdAt(Instant.now())
                .build());
    }

    private BacktestResultDto toDto(BacktestRunEntity e) {
        return BacktestResultDto.builder()
                .runId(e.getRunId())
                .seed(e.getSeed())
                .stressProfile(e.getStressProfile())
                .cycles(e.getCycles())
                .simulatedDurationSec(e.getSimulatedDurationSec())
                .totalPnl(e.getTotalPnl())
                .tradingPnl(e.getTradingPnl())
                .rewardPnl(e.getRewardPnl())
                .totalFills(e.getTotalFills())
                .toxicFills(e.getToxicFills())
                .totalFees(e.getTotalFees())
                .maxExposure(e.getMaxExposure())
                .maxDrawdown(e.getMaxDrawdown())
                .finalInventoryNet(e.getFinalInventoryNet())
                .avgProfitPerFill(e.getAvgProfitPerFill())
                .adverseSelectionRate(e.getAdverseSelectionRate())
                .winRate(e.getWinRate())
                .activeMarkets(e.getActiveMarkets())
                .elapsedMs(e.getElapsedMs())
                .createdAt(e.getCreatedAt())
                .build();
    }

    private MonteCarloResultDto mcToDto(MonteCarloRunEntity e) {
        return MonteCarloResultDto.builder()
                .mcRunId(e.getMcRunId())
                .numSeeds(e.getNumSeeds())
                .stressProfile(e.getStressProfile())
                .cyclesPerRun(e.getCyclesPerRun())
                .avgPnl(e.getAvgPnl())
                .medianPnl(e.getMedianPnl())
                .stdPnl(e.getStdPnl())
                .minPnl(e.getMinPnl())
                .maxPnl(e.getMaxPnl())
                .winRate(e.getWinRate())
                .avgDrawdown(e.getAvgDrawdown())
                .maxDrawdown(e.getMaxDrawdown())
                .avgFills(e.getAvgFills())
                .avgToxicFills(e.getAvgToxicFills())
                .sharpeRatio(e.getSharpeRatio())
                .elapsedMs(e.getElapsedMs())
                .createdAt(e.getCreatedAt())
                .build();
    }

    private StressProfile parseProfile(String name) {
        if (name == null || name.isBlank()) return StressProfile.BASELINE;
        try { return StressProfile.valueOf(name.toUpperCase()); }
        catch (Exception e) { return StressProfile.BASELINE; }
    }

    private static BigDecimal bd(double val) {
        return BigDecimal.valueOf(val).setScale(4, RoundingMode.HALF_UP);
    }
}
