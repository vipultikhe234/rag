package com.productrag.config;

import java.net.http.HttpClient;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;

/**
 * Applies explicit connect/read timeouts to the HTTP clients used by the Spring AI OpenAI client
 * (RestClient for blocking calls, WebClient for streamed answers), so a slow provider can never block
 * a request thread or the sync worker indefinitely.
 */
@Configuration
public class AiHttpConfig {

    @Bean
    public RestClientCustomizer aiRestClientTimeouts(AppProperties properties) {
        return builder -> {
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient(properties));
            requestFactory.setReadTimeout(properties.ai().readTimeout());
            builder.requestFactory(requestFactory);
        };
    }

    /** Streams additionally bound the gap between chunks in {@code SpringAiLlmClient#stream}. */
    @Bean
    public WebClientCustomizer aiWebClientTimeouts(AppProperties properties) {
        return builder -> {
            JdkClientHttpConnector connector = new JdkClientHttpConnector(httpClient(properties));
            connector.setReadTimeout(properties.ai().readTimeout());
            builder.clientConnector(connector);
        };
    }

    private static HttpClient httpClient(AppProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.ai().connectTimeout())
                .build();
    }
}
