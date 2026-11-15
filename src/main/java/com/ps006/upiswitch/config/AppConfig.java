package com.ps006.upiswitch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Spring Data JPA and Transaction Management Configuration.
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.ps006.upiswitch")
public class AppConfig {
}

