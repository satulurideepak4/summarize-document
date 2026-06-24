package com.example.summarize.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class OpenAiRestClientConfig {

    @Value("${spring.ai.openai.api-key:}")
    private String openaiApiKey;

    @Bean
    public RestClient openAiRestClient() {
        // JDK HttpClient with explicit timeouts - the default RestClient has no timeout,
        // which is dangerous for large file uploads.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .baseUrl("https://api.openai.com")
                .defaultHeader("Authorization", "Bearer " + openaiApiKey)
                .build();
    }
}
