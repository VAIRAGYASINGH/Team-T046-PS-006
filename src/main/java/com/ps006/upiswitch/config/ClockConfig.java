package com.ps006.upiswitch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Provides injectable Clock bean for deterministic time handling and testing.
 */
@Configuration
public class ClockConfig {

    private final SwitchProperties properties;

    public ClockConfig(SwitchProperties properties) {
        this.properties = properties;
    }

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of(properties.getTimezone()));
    }
}

