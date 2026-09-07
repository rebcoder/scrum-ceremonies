package com.scrumceremonies.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;


@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketSecurityInterceptor securityInterceptor;

    public WebSocketConfig(WebSocketSecurityInterceptor securityInterceptor) {
        this.securityInterceptor = securityInterceptor;
    }

    @Value("${spring.rabbitmq.host}")
    private String relayHost;

    @Value("${spring.rabbitmq.stomp.port}")
    private int relayPort;

    @Value("${spring.rabbitmq.username}")
    private String username;

    @Value("${spring.rabbitmq.password}")
    private String password;

    @Value("${app.use.simple-broker:true}")
    private boolean useSimpleBroker;

    @Value("${app.cors.allowed-origins:http://localhost:8080,http://localhost:3000}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        if (useSimpleBroker) {
            // TODO: Production Scaling Limitation
            // SimpleBroker does NOT scale across multiple container instances.
            // Messages sent from one instance will NOT reach WebSocket connections on other instances.
            // 
            // For horizontal scaling, use one of:
            // 1. Redis Pub/Sub with StompBrokerRelay (recommended for Redis users)
            // 2. RabbitMQ STOMP broker (set app.use.simple-broker=false)
            // 3. Apache Kafka with custom message broker
            //
            // Current setup: Single-instance only. Multiple instances will have isolated WebSocket connections.
            config.enableSimpleBroker("/topic");
        } else {
            config.enableStompBrokerRelay("/topic")
                    .setRelayHost(relayHost)
                    .setRelayPort(relayPort)
                    .setSystemLogin(username)
                    .setSystemPasscode(password)
                    .setClientLogin(username)
                    .setClientPasscode(password);
        }

        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Anonymous product: no authentication interceptor. Message rate limiting remains.
        registration.interceptors(securityInterceptor);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        String[] patterns = origins.isEmpty() ? new String[]{"*"} : origins.toArray(new String[0]);
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(patterns)
                .withSockJS()
                .setSuppressCors(false)
                .setSessionCookieNeeded(true);
    }
}

