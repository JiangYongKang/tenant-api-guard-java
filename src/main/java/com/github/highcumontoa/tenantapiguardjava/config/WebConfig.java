package com.github.highcumontoa.tenantapiguardjava.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.github.highcumontoa.tenantapiguardjava.guard.GuardInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final GuardInterceptor guardInterceptor;

    public WebConfig(GuardInterceptor guardInterceptor) {
        this.guardInterceptor = guardInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guardInterceptor).addPathPatterns("/api/**");
    }
}
