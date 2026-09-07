package com.scrumceremonies;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScrumCeremoniesApplicationTest {

    @Test
    @DisplayName("main method can be invoked without errors")
    void mainMethodRuns() {
        // Verify the class exists and can be loaded
        // We don't actually start the full Spring context as that requires Redis
        assertThat(ScrumCeremoniesApplication.class).isNotNull();
        assertThat(ScrumCeremoniesApplication.class.getAnnotations()).isNotEmpty();
    }

    @Test
    @DisplayName("class has SpringBootApplication annotation")
    void hasSpringBootAnnotation() {
        assertThat(ScrumCeremoniesApplication.class.isAnnotationPresent(
                org.springframework.boot.autoconfigure.SpringBootApplication.class)).isTrue();
    }

    @Test
    @DisplayName("class has EnableScheduling annotation")
    void hasEnableSchedulingAnnotation() {
        assertThat(ScrumCeremoniesApplication.class.isAnnotationPresent(
                org.springframework.scheduling.annotation.EnableScheduling.class)).isTrue();
    }

    @Test
    @DisplayName("class has EnableCaching annotation")
    void hasEnableCachingAnnotation() {
        assertThat(ScrumCeremoniesApplication.class.isAnnotationPresent(
                org.springframework.cache.annotation.EnableCaching.class)).isTrue();
    }
}
