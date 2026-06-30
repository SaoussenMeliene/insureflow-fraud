package com.insureflow.fraudservice.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FraudResponse {
    private String  claimId;
    private String  fraudType;      // NO_FRAUD / PRICE_INFLATION
    // DESCRIPTION_MISMATCH
    // INTERNAL_INCONSISTENCY
    private List<String> fraudTypes;
    private double  fraudScore;     // 0.0 à 1.0
    private boolean fraudDetected;  // true si fraude
    private String  fraudReasoning; // explication
    private double  confidence;     // confiance de l'analyse
    private long    processingTimeMs;
    private String  agentName;
}
