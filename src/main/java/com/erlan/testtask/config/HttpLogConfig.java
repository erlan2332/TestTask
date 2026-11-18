package com.erlan.testtask.config;

import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.zalando.logbook.HeaderFilter;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.PathFilter;
import org.zalando.logbook.QueryFilter;
import org.zalando.logbook.Strategy;
import org.zalando.logbook.core.HeaderFilters;
import org.zalando.logbook.servlet.LogbookFilter;

@Configuration(proxyBeanMethods = false)
public class HttpLogConfig {

    private static final String EXTERNAL_PATH = "/api/external/objects";
    private static final Pattern SAFE_PATH =
            Pattern.compile("/api/tasks(?:/\\d+)?|/api/external/objects");

    @Bean("logbookFilter")
    FilterRegistrationBean<LogbookFilter> logbookFilter(Logbook logbook) {
        FilterRegistrationBean<LogbookFilter> registration =
                new FilterRegistrationBean<>(new LogbookFilter(logbook));
        registration.setName("logbookFilter");
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    @Bean
    Strategy strategy() {
        return new Strategy() {
            @Override
            public HttpRequest process(HttpRequest request) throws IOException {
                return logTaskBody(request) ? request.withBody() : request.withoutBody();
            }

            @Override
            public HttpResponse process(HttpRequest request, HttpResponse response) throws IOException {
                return logTaskBody(request) ? response.withBody() : response.withoutBody();
            }
        };
    }

    private static boolean logTaskBody(HttpRequest request) {
        String path = request.getPath();
        return !EXTERNAL_PATH.equals(path) && SAFE_PATH.matcher(path).matches();
    }

    @Bean
    QueryFilter queryFilter() {
        return query -> "";
    }

    @Bean
    HeaderFilter headerFilter() {
        return HeaderFilters.removeHeaders(name -> true);
    }

    @Bean
    PathFilter pathFilter() {
        return path -> SAFE_PATH.matcher(path).matches() ? path : "[redacted path]";
    }
}
