package com.example.chatbot.rag;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SearchDebugResponse {

    private String query;
    private String enhancedQuery;
    private List<SearchResult> esResults;
    private List<SearchResult> vectorResults;
    private List<HybridSearchResult> rrfResults;
}
