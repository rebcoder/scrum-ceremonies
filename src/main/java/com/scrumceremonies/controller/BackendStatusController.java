package com.scrumceremonies.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simple endpoint to verify the backend is running.
 * Returns a clear "Backend is running" message with basic details and Redis status.
 * No authentication required; safe for health checks and ops.
 */
@RestController
public class BackendStatusController {

	@Value("${spring.application.name:scrum-ceremonies}")
	private String applicationName;

	@Value("${app.version:0.0.1-SNAPSHOT}")
	private String version;

	private final HealthContributorRegistry healthContributorRegistry;

	public BackendStatusController(HealthContributorRegistry healthContributorRegistry) {
		this.healthContributorRegistry = healthContributorRegistry;
	}

	@GetMapping(value = "/api/status", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> status() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", "running");
		body.put("message", "Backend is running");
		body.put("application", applicationName);
		body.put("version", version);
		body.put("redis", redisStatus());
		return ResponseEntity.ok(body);
	}

	private String redisStatus() {
		try {
			var contributor = healthContributorRegistry.getContributor("redis");
			if (contributor instanceof HealthIndicator indicator) {
				Health health = indicator.health();
				String code = health.getStatus() != null ? health.getStatus().getCode() : "UNKNOWN";
				return code.toLowerCase();
			}
		} catch (Exception ignored) {
			// Redis not configured or contributor missing
		}
		return "unknown";
	}
}
