package com.scrumceremonies.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Test configuration for Redis using Testcontainers.
 * Provides isolated Redis instance for integration tests.
 * 
 * This configuration uses Testcontainers to spin up a Redis container
 * for each test class that imports it. The container is reused across
 * tests in the same JVM when possible.
 * 
 * Usage:
 * Add @Import(RedisTestConfiguration.class) to your test class.
 * 
 * Note: Requires Docker to be running.
 */
@TestConfiguration
public class RedisTestConfiguration {
    
    // Static container shared across all test classes in the same JVM
    // This improves test performance by reusing the same container
    private static final GenericContainer<?> redisContainer;
    
    static {
        redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .withReuse(true);
        redisContainer.start();
    }
    
    @Bean
    @Primary
    public RedisConnectionFactory testRedisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisContainer.getHost());
        config.setPort(redisContainer.getFirstMappedPort());
        return new LettuceConnectionFactory(config);
    }
}

