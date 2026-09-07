package com.scrumceremonies.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI scrumCeremoniesOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Scrum Ceremonies API")
                        .description("REST API for Scrum Ceremonies — anonymous Planning Poker, "
                                + "Retro Board, and Mood Check. No authentication; access is by room code.")
                        .version("1.0.0")
                        .contact(new Contact().name("Scrum Ceremonies")));
    }
}
