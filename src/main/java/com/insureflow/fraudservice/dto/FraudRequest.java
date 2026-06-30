package com.insureflow.fraudservice.dto;

import lombok.Data;

import java.util.List;

@Data
public class FraudRequest {
    private String claimId;
    private String policyNumber;
    private String claimType;
    private String description;
    private List<String> photoUrls;

    // Prix déclaré par le client (optionnel)
    private Double clientEstimatedCost;

    // Prix calculé par le système (Tavily + Groq)
    private Double systemEstimatedCostMin;
    private Double systemEstimatedCostMax;

    // Pièces endommagées détectées
    private List<String> damagedParts;

}
