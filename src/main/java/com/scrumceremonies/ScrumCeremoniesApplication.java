package com.scrumceremonies;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableCaching
@EnableAsync
public class ScrumCeremoniesApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(ScrumCeremoniesApplication.class);
		// Enable lazy initialization for faster startup
		// Beans are created on-demand, reducing cold start time
		app.setLazyInitialization(true);
		app.run(args);
	}

}
