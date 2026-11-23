package com.specshield.ledgerline.config;

import com.specshield.ledgerline.chaos.ChaosProperties;
import com.specshield.ledgerline.chaos.ScenarioHook;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChaosProperties.class)
public class ChaosConfig {

    /** Does nothing in production. Tests override it with a @Primary bean to hold runs open. */
    @Bean
    ScenarioHook scenarioHook() {
        return run -> {};
    }
}
