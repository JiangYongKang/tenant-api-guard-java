package com.github.highcumontoa.tenantapiguardjava.config;

import com.github.highcumontoa.tenantapiguardjava.common.TimeSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配保护能力所需的基础 Bean。
 */
@Configuration
@EnableConfigurationProperties(GuardProperties.class)
public class GuardConfiguration {

    @Bean
    public TimeSource timeSource() {
        return new TimeSource.SystemTimeSource();
    }
}
