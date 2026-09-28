package ru.heatnet.jobs;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Пул тяжёлого расчёта: строго 2 воркера (ТЗ 3.2, 50 пользователей = polling, не 50 трассировок).
 */
@Configuration
public class JobExecutorConfig {

    public static final String EXECUTOR = "calculationExecutor";

    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor calculationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("heatnet-calc-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
