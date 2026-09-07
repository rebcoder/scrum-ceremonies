package com.scrumceremonies.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * Startup profiling configuration.
 * Logs startup phases and timing to help identify bottlenecks.
 * 
 * This component is lightweight and doesn't block startup.
 */
@Component
public class StartupProfilingConfig implements ApplicationListener<ContextRefreshedEvent> {
    
    private static final Logger log = LoggerFactory.getLogger(StartupProfilingConfig.class);
    private static long contextStartTime;
    
    static {
        contextStartTime = System.currentTimeMillis();
    }
    
    @Override
    public void onApplicationEvent(@NonNull ContextRefreshedEvent event) {
        long contextInitTime = System.currentTimeMillis() - contextStartTime;
        log.info("Spring context initialized in {} ms", contextInitTime);
    }
    
    /**
     * Log application ready event (after all beans initialized and server started)
     */
    @Component
    public static class ApplicationReadyListener implements ApplicationListener<ApplicationReadyEvent> {
        private static final Logger log = LoggerFactory.getLogger(ApplicationReadyListener.class);
        private static final long appStartTime = System.currentTimeMillis();
        
        @Override
        public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
            long totalStartupTime = System.currentTimeMillis() - appStartTime;
            log.info("Application ready in {} ms ({} seconds)", totalStartupTime, totalStartupTime / 1000.0);
            
            // Log JVM info for performance analysis
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            
            log.info("JVM Memory - Max: {} MB, Total: {} MB, Used: {} MB, Free: {} MB",
                    maxMemory / (1024 * 1024),
                    totalMemory / (1024 * 1024),
                    usedMemory / (1024 * 1024),
                    freeMemory / (1024 * 1024));
        }
    }
}

