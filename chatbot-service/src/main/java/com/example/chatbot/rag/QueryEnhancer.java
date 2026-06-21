package com.example.chatbot.rag;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class QueryEnhancer {

    private final RagProperties ragProperties;
    private final Map<String, List<String>> synonyms = new LinkedHashMap<>();

    @PostConstruct
    public void loadSynonyms() {
        synonyms.clear();
        String location = ragProperties.getQueryEnhancer().getSynonymFile();
        Resource resource = new DefaultResourceLoader().getResource(location);
        if (!resource.exists()) {
            log.warn("[QueryEnhancer] synonym file not found, location={}", location);
            return;
        }
        try (InputStream inputStream = resource.getInputStream()) {
            Object loaded = new Yaml().load(inputStream);
            if (!(loaded instanceof Map<?, ?> root)) {
                return;
            }
            Object synonymNode = root.get("synonyms");
            if (!(synonymNode instanceof Map<?, ?> synonymMap)) {
                return;
            }
            for (Map.Entry<?, ?> entry : synonymMap.entrySet()) {
                String key = String.valueOf(entry.getKey()).trim();
                if (key.isBlank()) {
                    continue;
                }
                List<String> values = toStringList(entry.getValue());
                if (!values.isEmpty()) {
                    synonyms.put(key, values);
                }
            }
            log.info("[QueryEnhancer] loaded synonym groups={}, location={}", synonyms.size(), location);
        } catch (Exception e) {
            log.warn("[QueryEnhancer] load synonym file failed, location={}, error={}", location, e.getMessage());
        }
    }

    public EnhancedQuery enhance(String query) {
        String original = query == null ? "" : query.trim();
        if (original.isBlank() || !ragProperties.getQueryEnhancer().isEnabled()) {
            return new EnhancedQuery(original, original, List.of(), List.of());
        }

        String lower = original.toLowerCase(Locale.ROOT);
        List<String> matchedKeys = new ArrayList<>();
        List<String> expandedTerms = new ArrayList<>();
        int maxExpandedTerms = Math.max(0, ragProperties.getQueryEnhancer().getMaxExpandedTerms());
        for (Map.Entry<String, List<String>> entry : synonyms.entrySet()) {
            if (maxExpandedTerms > 0 && expandedTerms.size() >= maxExpandedTerms) {
                break;
            }
            if (!lower.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            matchedKeys.add(entry.getKey());
            for (String value : entry.getValue()) {
                if (expandedTerms.size() >= maxExpandedTerms) {
                    break;
                }
                if (!containsIgnoreCase(original, value) && !containsIgnoreCase(expandedTerms, value)) {
                    expandedTerms.add(value);
                }
            }
        }

        String enhanced = original;
        if (!expandedTerms.isEmpty()) {
            enhanced = original + " " + String.join(" ", expandedTerms);
        }
        log.info("[QueryEnhancer] query='{}', enhancedQuery='{}', matchedKeys={}, expandedTerms={}",
                truncate(original), truncate(enhanced), matchedKeys, expandedTerms);
        return new EnhancedQuery(original, enhanced, matchedKeys, expandedTerms);
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        for (Object item : iterable) {
            if (item == null) {
                continue;
            }
            String term = String.valueOf(item).trim();
            if (!term.isBlank()) {
                results.add(term);
            }
        }
        return results;
    }

    private boolean containsIgnoreCase(String text, String value) {
        return text != null && value != null
                && text.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
    }

    private boolean containsIgnoreCase(List<String> terms, String value) {
        for (String term : terms) {
            if (term.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 80 ? value.substring(0, 80) + "..." : value;
    }

    public record EnhancedQuery(String originalQuery,
                                String enhancedQuery,
                                List<String> matchedKeys,
                                List<String> expandedTerms) {
    }
}
