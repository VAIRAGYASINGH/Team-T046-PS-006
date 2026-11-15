package com.ps006.upiswitch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * PS-006: UPI Payment Switch Application Entry Point.
 * Backend-only, command-file-driven financial transaction processing system.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.ps006.upiswitch.config")
public class UpiPaymentSwitchApplication {

    public static void main(String[] args) {
        SpringApplication.run(UpiPaymentSwitchApplication.class, args);
    }
}

