package com.example.chatbot.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 视觉模型配置：通过 Ollama 的 OpenAI 兼容 API 创建独立的视觉 ChatModel Bean
 * 这样可以在运行时动态选择使用 llava 等视觉模型，而不影响默认的 Ollama 文本模型
 */
@Configuration
public class VisionModelConfig {

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${app.chatbot.vision-model:llava:latest}")
    private String visionModel;

    @Bean("visionChatModel")
    public ChatModel visionChatModel() {
        // Ollama 的 OpenAI 兼容端点：OpenAiApi 会自动追加 /v1/chat/completions
        // 所以 baseUrl 直接用 Ollama 根地址即可，不要手动拼 /v1
        String baseUrl = ollamaBaseUrl.endsWith("/")
                ? ollamaBaseUrl.substring(0, ollamaBaseUrl.length() - 1)
                : ollamaBaseUrl;

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey("ollama")
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(visionModel)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }
}
