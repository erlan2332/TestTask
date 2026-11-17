package com.erlan.testtask.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String TASKS_CACHE = "tasks";

    @Bean
    RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return builder -> builder.transactionAware();
    }

    @Bean
    ApplicationRunner clearTaskCacheOnStartup(CacheManager cacheManager) {
        return args -> {
            Cache cache = cacheManager.getCache(TASKS_CACHE);
            if (cache == null) {
                throw new IllegalStateException("Tasks cache is not configured");
            }
            cache.clear();
        };
    }
}
