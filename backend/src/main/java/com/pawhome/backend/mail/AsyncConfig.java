package com.pawhome.backend.mail;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Turns on @Async and @Scheduled.
 *
 * WITHOUT @EnableAsync, EVERY @Async METHOD RUNS INLINE AND SILENTLY. Nothing
 * fails, nothing warns -- the SMTP round trip simply happens on the request
 * thread again, and the latency the annotation was added to avoid comes
 * straight back. It is the quietest way to lose a performance fix.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Small on purpose. Mail is the only async work here, and Gmail will
        // throttle long before a bigger pool helps.
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // Bounded. An unbounded queue turns a mail outage into an OutOfMemory
        // error, because every undeliverable message piles up in it forever.
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("pawhome-async-");
        executor.initialize();
        return executor;
    }
}
