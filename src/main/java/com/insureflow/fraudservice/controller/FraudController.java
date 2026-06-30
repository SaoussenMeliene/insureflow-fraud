package com.insureflow.fraudservice.controller;

import com.insureflow.fraudservice.dto.FraudRequest;
import com.insureflow.fraudservice.dto.FraudResponse;
import com.insureflow.fraudservice.service.FraudService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Slf4j
@RequestMapping("/api/fraud")
@RequiredArgsConstructor
public class FraudController {
    private final FraudService fraudService;
    // ── POST /api/fraud/analyze ──────────────────
    @PostMapping("/analyze")
    public ResponseEntity<FraudResponse> analyze(
            @RequestBody FraudRequest request) {
        log.info("Analyse fraude : {}", request.getClaimId());
        return ResponseEntity.ok(
                fraudService.analyze(request));
    }

    // ── GET /api/fraud/health ────────────────────
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status",  "UP",
                "service", "fraud-service",
                "port",    "8087"
        ));
    }

}
