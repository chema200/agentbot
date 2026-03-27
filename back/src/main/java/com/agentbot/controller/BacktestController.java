package com.agentbot.controller;

import com.agentbot.engine.StressProfile;
import com.agentbot.model.BacktestResultDto;
import com.agentbot.model.MonteCarloResultDto;
import com.agentbot.service.BacktestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;

    @PostMapping("/run")
    public ResponseEntity<BacktestResultDto> runBacktest(
            @RequestParam(defaultValue = "500") int cycles,
            @RequestParam(defaultValue = "42") long seed,
            @RequestParam(defaultValue = "BASELINE") String profile) {
        return ResponseEntity.ok(backtestService.runSingleBacktest(cycles, seed, profile));
    }

    @PostMapping("/monte-carlo")
    public ResponseEntity<MonteCarloResultDto> runMonteCarlo(
            @RequestParam(defaultValue = "200") int cycles,
            @RequestParam(defaultValue = "20") int seeds,
            @RequestParam(defaultValue = "BASELINE") String profile) {
        return ResponseEntity.ok(backtestService.runMonteCarlo(cycles, seeds, profile));
    }

    @PostMapping("/stress-suite")
    public ResponseEntity<Map<String, MonteCarloResultDto>> runStressSuite(
            @RequestParam(defaultValue = "200") int cycles,
            @RequestParam(defaultValue = "10") int seeds) {
        Map<String, MonteCarloResultDto> results = Arrays.stream(StressProfile.values())
                .collect(Collectors.toMap(
                        StressProfile::name,
                        sp -> backtestService.runMonteCarlo(cycles, seeds, sp.name()),
                        (a, b) -> a,
                        java.util.LinkedHashMap::new));
        return ResponseEntity.ok(results);
    }

    @GetMapping("/runs")
    public ResponseEntity<List<BacktestResultDto>> getRecentRuns() {
        return ResponseEntity.ok(backtestService.getRecentRuns());
    }

    @GetMapping("/runs/{profile}")
    public ResponseEntity<List<BacktestResultDto>> getRunsByProfile(@PathVariable String profile) {
        return ResponseEntity.ok(backtestService.getRunsByProfile(profile.toUpperCase()));
    }

    @GetMapping("/monte-carlo/runs")
    public ResponseEntity<List<MonteCarloResultDto>> getRecentMcRuns() {
        return ResponseEntity.ok(backtestService.getRecentMcRuns());
    }

    @GetMapping("/comparison")
    public ResponseEntity<Map<String, Object>> getComparison() {
        return ResponseEntity.ok(backtestService.getComparisonAcrossProfiles());
    }

    @GetMapping("/profiles")
    public ResponseEntity<List<String>> getProfiles() {
        return ResponseEntity.ok(
                Arrays.stream(StressProfile.values())
                        .map(StressProfile::name)
                        .collect(Collectors.toList()));
    }
}
