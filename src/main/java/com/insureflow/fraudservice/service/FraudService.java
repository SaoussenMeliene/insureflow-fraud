package com.insureflow.fraudservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insureflow.fraudservice.dto.FraudRequest;
import com.insureflow.fraudservice.dto.FraudResponse;

// ✅ LangChain4j imports
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FraudService {

    private final ObjectMapper objectMapper;

    @Value("${groq.api-key}")       private String groqApiKey;
    @Value("${groq.model}")         private String groqModel;
    @Value("${groq.base-url}")      private String groqBaseUrl;
    @Value("${groq.vision-model}")  private String groqVisionModel;

    private static final double PRICE_INFLATION_THRESHOLD = 0.30;
    private static final double HIGH_FRAUD_SCORE          = 0.70;

    // ✅ LangChain4j — modèle texte (LLaMA 3.3)
    private ChatLanguageModel chatModel;

    // ✅ LangChain4j — modèle vision (LLaMA 4 Scout)
    private ChatLanguageModel visionModel;

    @PostConstruct
    public void initModels() {
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(groqBaseUrl)
                .apiKey(groqApiKey)
                .modelName(groqModel)
                .temperature(0.1)
                .maxTokens(300)
                .build();

        this.visionModel = OpenAiChatModel.builder()
                .baseUrl(groqBaseUrl)
                .apiKey(groqApiKey)
                .modelName(groqVisionModel)
                .temperature(0.1)
                .maxTokens(500)
                .build();

        log.info("✅ FraudService LangChain4j initialisé — texte={} vision={}",
                groqModel, groqVisionModel);
    }

    // ════════════════════════════════════════════
    // ANALYSE PRINCIPALE — 3 analyses simultanées
    // ════════════════════════════════════════════
    public FraudResponse analyze(FraudRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("Analyse fraude — claimId={} type={}",
                request.getClaimId(), request.getClaimType());

        try {
            FraudResponse priceAnalysis     = analyzePriceInflation(request, startTime);
            FraudResponse coherenceAnalysis = analyzeCoherence(request, startTime);
            FraudResponse photoAnalysis     = analyzePhotoCoherence(request, startTime);

            double finalScore = Math.max(
                    Math.max(priceAnalysis.getFraudScore(),
                            coherenceAnalysis.getFraudScore()),
                    photoAnalysis.getFraudScore()
            );

            boolean fraudDetected = finalScore >= HIGH_FRAUD_SCORE;
            String fraudType = determineFraudType(priceAnalysis, coherenceAnalysis, photoAnalysis);

            List<String> allFraudTypes = new ArrayList<>();
            if (!"NO_FRAUD".equals(priceAnalysis.getFraudType()))
                allFraudTypes.add(priceAnalysis.getFraudType());
            if (!"NO_FRAUD".equals(coherenceAnalysis.getFraudType()))
                allFraudTypes.add(coherenceAnalysis.getFraudType());
            if (!"NO_FRAUD".equals(photoAnalysis.getFraudType()))
                allFraudTypes.add(photoAnalysis.getFraudType());
            if (allFraudTypes.isEmpty())
                allFraudTypes.add("NO_FRAUD");

            log.info("Résultat fraude — type={} score={} detected={}",
                    fraudType, finalScore, fraudDetected);

            return FraudResponse.builder()
                    .claimId(request.getClaimId())
                    .fraudType(fraudType)
                    .fraudTypes(allFraudTypes)
                    .fraudScore(finalScore)
                    .fraudDetected(fraudDetected)
                    .fraudReasoning(buildReasoning(priceAnalysis, coherenceAnalysis, photoAnalysis))
                    .confidence(0.90)
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .agentName("FraudAgent")
                    .build();

        } catch (Exception e) {
            log.error("Erreur analyse fraude : {}", e.getMessage());
            return FraudResponse.builder()
                    .claimId(request.getClaimId())
                    .fraudType("UNKNOWN")
                    .fraudScore(0.0)
                    .fraudDetected(false)
                    .fraudReasoning("Erreur analyse : " + e.getMessage())
                    .confidence(0.0)
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .agentName("FraudAgent")
                    .build();
        }
    }

    // ════════════════════════════════════════════
    // ANALYSE 1 — Prix client vs système (inchangée)
    // ════════════════════════════════════════════
    private FraudResponse analyzePriceInflation(FraudRequest request, long startTime) {
        if (request.getClientEstimatedCost() == null || request.getClientEstimatedCost() == 0) {
            return FraudResponse.builder()
                    .claimId(request.getClaimId())
                    .fraudType("NO_FRAUD").fraudScore(0.0).fraudDetected(false)
                    .fraudReasoning("Aucun prix client fourni")
                    .confidence(1.0).processingTimeMs(0).agentName("FraudAgent").build();
        }

        double clientCost    = request.getClientEstimatedCost();
        double systemCostMid = (request.getSystemEstimatedCostMin()
                + request.getSystemEstimatedCostMax()) / 2;

        if (systemCostMid == 0) {
            return FraudResponse.builder()
                    .claimId(request.getClaimId())
                    .fraudType("NO_FRAUD").fraudScore(0.0).fraudDetected(false)
                    .fraudReasoning("Estimation système indisponible.")
                    .confidence(0.5).processingTimeMs(0).agentName("FraudAgent").build();
        }

        double ecart = (clientCost - systemCostMid) / systemCostMid;
        log.info("Prix client={} système={} écart={}%",
                clientCost, systemCostMid, Math.round(ecart * 100));

        double fraudScore;
        String fraudType;
        String reasoning;

        if (ecart > PRICE_INFLATION_THRESHOLD) {
            fraudScore = Math.min(ecart, 1.0);
            fraudType  = "PRICE_INFLATION";
            reasoning  = String.format(
                    "Client déclare %.0f TND, système estime %.0f TND. Écart %.0f%% (seuil: %.0f%%).",
                    clientCost, systemCostMid, ecart * 100, PRICE_INFLATION_THRESHOLD * 100);
        } else if (ecart < -PRICE_INFLATION_THRESHOLD) {
            fraudScore = 0.2;
            fraudType  = "PRICE_UNDERESTIMATION";
            reasoning  = String.format("Prix client %.0f TND inférieur au système %.0f TND.",
                    clientCost, systemCostMid);
        } else {
            fraudScore = 0.0;
            fraudType  = "NO_FRAUD";
            reasoning  = String.format(
                    "Prix client %.0f TND cohérent avec système %.0f TND (écart %.0f%%).",
                    clientCost, systemCostMid, ecart * 100);
        }

        return FraudResponse.builder()
                .claimId(request.getClaimId())
                .fraudType(fraudType).fraudScore(fraudScore)
                .fraudDetected(fraudScore >= HIGH_FRAUD_SCORE)
                .fraudReasoning(reasoning).confidence(0.95)
                .processingTimeMs(System.currentTimeMillis() - startTime)
                .agentName("FraudAgent").build();
    }

    // ════════════════════════════════════════════
    // ANALYSE 2 — Cohérence texte (✅ LangChain4j)
    // ════════════════════════════════════════════
    private FraudResponse analyzeCoherence(FraudRequest request, long startTime) {
        try {
            String expertDomain = switch (request.getClaimType() != null ?
                    request.getClaimType().toUpperCase() : "AUTO") {
                case "AUTO"     -> "assurance automobile en Tunisie";
                case "HOME"     -> "assurance multirisques habitation en Tunisie";
                case "HEALTH"   -> "assurance individuelle accidents et sante en Tunisie";
                case "SCOLAIRE" -> "assurance multirisques etablissement scolaire en Tunisie";
                case "OTHER"    -> "assurance multirisques divers et commerces en Tunisie";
                default         -> "assurance en Tunisie";
            };

            String exemples = switch (request.getClaimType() != null ?
                    request.getClaimType().toUpperCase() : "AUTO") {
                case "AUTO"     -> "Exemples AUTO : collision, accident pieton, vol vehicule, incendie vehicule.";
                case "HOME"     -> "Exemples HOME : degats des eaux, incendie cuisine, vol effraction.";
                case "HEALTH"   -> "Exemples HEALTH : fracture chute, hospitalisation, frais medicaux.";
                case "SCOLAIRE" -> "Exemples SCOLAIRE : accident eleve, blessure sport scolaire.";
                case "OTHER"    -> "Exemples OTHER : cambriolage local, vol materiel, incendie commercial.";
                default         -> "Exemples : accident corporel, degats materiels, vol.";
            };

            // ✅ Prompt complet pour LangChain4j
            String prompt = String.format("""
                    Tu es un expert en detection de fraude pour les %s.
                    Analyse la coherence du sinistre de type %s.
                    %s

                    CRITERES D'ANALYSE :
                    1. Description coherente avec le type %s ?
                       Si non → DESCRIPTION_MISMATCH, score 0.80
                    2. Contradictions internes ?
                       Si oui → INTERNAL_INCONSISTENCY, score 0.80
                    3. Elements endommages coherents avec la description ?
                       Si non → INTERNAL_INCONSISTENCY, score 0.75

                    SCORES : INTERNAL_INCONSISTENCY min 0.75 | DESCRIPTION_MISMATCH min 0.80 | NO_FRAUD = 0.0

                    JSON STRICT :
                    {
                      "fraudType": "NO_FRAUD"|"INTERNAL_INCONSISTENCY"|"DESCRIPTION_MISMATCH",
                      "fraudScore": 0.0,
                      "reasoning": "explication precise en francais"
                    }

                    SINISTRE :
                    - Type        : %s
                    - Description : %s
                    - Elements    : %s

                    Reponds UNIQUEMENT avec un objet JSON valide.
                    """,
                    expertDomain,
                    request.getClaimType(),
                    exemples,
                    request.getClaimType(),
                    request.getClaimType(),
                    request.getDescription(),
                    request.getDamagedParts() != null ?
                            request.getDamagedParts().toString() : "Non specifie"
            );

            // ✅ Appel LangChain4j — 1 seule ligne !
            String content = chatModel.generate(prompt);

            String clean = content.replaceAll("```json", "").replaceAll("```", "").trim();
            int start = clean.indexOf('{');
            int end   = clean.lastIndexOf('}');
            if (start != -1 && end != -1 && end > start)
                clean = clean.substring(start, end + 1);

            Map<String, Object> parsed = objectMapper.readValue(clean, Map.class);
            double fraudScore = ((Number) parsed.get("fraudScore")).doubleValue();

            log.info("Cohérence LangChain4j — fraudType={} score={}",
                    parsed.get("fraudType"), fraudScore);

            return FraudResponse.builder()
                    .claimId(request.getClaimId())
                    .fraudType((String) parsed.get("fraudType"))
                    .fraudScore(fraudScore)
                    .fraudDetected(fraudScore >= HIGH_FRAUD_SCORE)
                    .fraudReasoning((String) parsed.get("reasoning"))
                    .confidence(0.85)
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .agentName("FraudAgent").build();

        } catch (Exception e) {
            log.error("Erreur cohérence LangChain4j : {}", e.getMessage());
            return FraudResponse.builder()
                    .claimId(request.getClaimId())
                    .fraudType("NO_FRAUD").fraudScore(0.0).fraudDetected(false)
                    .fraudReasoning("Analyse cohérence indisponible")
                    .confidence(0.5).processingTimeMs(0).agentName("FraudAgent").build();
        }
    }

    // ════════════════════════════════════════════
    // ANALYSE 3 — Vision photos (✅ LangChain4j)
    // ════════════════════════════════════════════
    private FraudResponse analyzePhotoCoherence(FraudRequest request, long startTime) {

        if (request.getPhotoUrls() == null || request.getPhotoUrls().isEmpty()) {
            log.info("Pas de photos — analyse visuelle ignorée");
            return FraudResponse.builder()
                    .claimId(request.getClaimId())
                    .fraudType("NO_FRAUD").fraudScore(0.0).fraudDetected(false)
                    .fraudReasoning("Aucune photo fournie — analyse visuelle ignorée.")
                    .confidence(1.0).processingTimeMs(0).agentName("FraudAgent").build();
        }

        try {
            log.info("✅ Analyse photo LangChain4j Vision — claimId={} photos={}",
                    request.getClaimId(), request.getPhotoUrls().size());

            // ✅ Construction du prompt texte
            String textPrompt = String.format("""
                    Tu es un expert en detection de fraude pour les assurances %s en Tunisie.

                    %s

                    Le client declare ce sinistre :
                    - Type                        : %s
                    - Description                 : %s
                    - Pieces endommagees declarees: %s

                    ANALYSE EN 3 ETAPES :
                    1. Est-ce une vraie photo de sinistre ?
                       Si non (selfie, paysage...) → FAKE_PHOTO, score 0.90
                    2. Les pieces visibles correspondent-elles aux pieces declarees : %s ?
                       Si non → PARTS_MISMATCH, score 0.85
                    3. La description correspond-elle a la photo ?
                       Si non → PHOTO_MISMATCH, score 0.80

                    JSON STRICT :
                    {
                      "coherent": true,
                      "fraudType": "NO_FRAUD"|"FAKE_PHOTO"|"PARTS_MISMATCH"|"PHOTO_MISMATCH",
                      "fraudScore": 0.0,
                      "partsVisible": ["piece1"],
                      "partsMatch": true,
                      "reasoning": "explication en francais"
                    }
                    """,
                    request.getClaimType(),
                    getPhotoContext(request.getClaimType()),
                    request.getClaimType(),
                    request.getDescription(),
                    request.getDamagedParts() != null ?
                            String.join(", ", request.getDamagedParts()) : "Non specifie",
                    request.getDamagedParts() != null ?
                            String.join(", ", request.getDamagedParts()) : "Non specifie"
            );

            // ✅ Construction du UserMessage multimodal LangChain4j
            List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
            contents.add(TextContent.from(textPrompt));

            // ✅ Ajouter chaque photo comme ImageContent
            for (String url : request.getPhotoUrls()) {
                contents.add(ImageContent.from(url));
            }

            UserMessage userMessage = UserMessage.from(contents);

            // ✅ Appel Vision LangChain4j — propre et typé !
            String responseContent = visionModel.generate(
                    List.of(userMessage)
            ).content().text();

            String clean = responseContent
                    .replaceAll("```json", "").replaceAll("```", "").trim();
            int start = clean.indexOf('{');
            int end   = clean.lastIndexOf('}');
            if (start != -1 && end != -1 && end > start)
                clean = clean.substring(start, end + 1);

            Map<String, Object> parsed = objectMapper.readValue(clean, Map.class);
            double  fraudScore = ((Number) parsed.get("fraudScore")).doubleValue();
            String  fraudType  = (String)  parsed.get("fraudType");
            String  reasoning  = (String)  parsed.get("reasoning");

            log.info("✅ Photo Vision LangChain4j — fraudType={} score={}", fraudType, fraudScore);

            return FraudResponse.builder()
                    .claimId(request.getClaimId())
                    .fraudType(fraudType).fraudScore(fraudScore)
                    .fraudDetected(fraudScore >= HIGH_FRAUD_SCORE)
                    .fraudReasoning(reasoning).confidence(0.90)
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .agentName("FraudAgent").build();

        } catch (Exception e) {
            log.error("Erreur analyse photo Vision LangChain4j : {}", e.getMessage());
            return FraudResponse.builder()
                    .claimId(request.getClaimId())
                    .fraudType("NO_FRAUD").fraudScore(0.0).fraudDetected(false)
                    .fraudReasoning("Analyse photo indisponible — bénéfice du doute.")
                    .confidence(0.5).processingTimeMs(0).agentName("FraudAgent").build();
        }
    }

    // ════════════════════════════════════════════
    // CONTEXTE PHOTO — inchangé
    // ════════════════════════════════════════════
    private String getPhotoContext(String claimType) {
        return switch (claimType != null ? claimType.toUpperCase() : "OTHER") {
            case "AUTO"     -> "Type AUTO — vehicule endommage attendu. Invalide si selfie/maison/paysage.";
            case "HOME"     -> "Type HOME — dommages logement attendus. Invalide si voiture/selfie.";
            case "HEALTH"   -> "Type HEALTH — blessure visible ou document medical attendu.";
            case "SCOLAIRE" -> "Type SCOLAIRE — contexte scolaire attendu (eleve, classe, cour).";
            default         -> "Type OTHER — local commercial endommage attendu.";
        };
    }

    // ════════════════════════════════════════════
    // HELPERS — inchangés
    // ════════════════════════════════════════════
    private String determineFraudType(
            FraudResponse price, FraudResponse coherence, FraudResponse photo) {
        double maxScore = Math.max(
                Math.max(price.getFraudScore(), coherence.getFraudScore()),
                photo.getFraudScore());
        if (maxScore == photo.getFraudScore())     return photo.getFraudType();
        if (maxScore == price.getFraudScore())     return price.getFraudType();
        return coherence.getFraudType();
    }

    private String buildReasoning(
            FraudResponse price, FraudResponse coherence, FraudResponse photo) {
        StringBuilder sb = new StringBuilder();
        if (!"NO_FRAUD".equals(price.getFraudType()))
            sb.append("PRIX : ").append(price.getFraudReasoning());
        if (!"NO_FRAUD".equals(coherence.getFraudType())) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("COHERENCE : ").append(coherence.getFraudReasoning());
        }
        if (!"NO_FRAUD".equals(photo.getFraudType())) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("PHOTO : ").append(photo.getFraudReasoning());
        }
        if (sb.length() == 0)
            sb.append("Aucune fraude détectée. Sinistre cohérent.");
        return sb.toString();
    }
}