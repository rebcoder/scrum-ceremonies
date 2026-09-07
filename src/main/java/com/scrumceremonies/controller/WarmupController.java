package com.scrumceremonies.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Lightweight warmup endpoint.
 *
 * Goals:
 * - Returns HTTP 200 immediately
 * - No DB/Redis/external calls
 * - No authentication required
 * - Safe to call post-deploy to reduce first-request latency
 * - Does not alter application state or expose sensitive data
 */
@RestController
public class WarmupController {

	@GetMapping("/_warmup")
	public ResponseEntity<String> warmup() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_PLAIN);
		// Ensure no caching of warmup response by intermediaries
		headers.setCacheControl(CacheControl.noStore().mustRevalidate().getHeaderValue());
		// Minimal body to avoid exposing any internal details
		return ResponseEntity.ok()
				.headers(headers)
				.body("OK");
	}
}


