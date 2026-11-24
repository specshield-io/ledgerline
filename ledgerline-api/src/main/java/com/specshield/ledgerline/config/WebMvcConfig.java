package com.specshield.ledgerline.config;

import com.specshield.ledgerline.web.OrderIdMdcInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new OrderIdMdcInterceptor()).addPathPatterns("/api/v1/orders/*", "/api/v1/orders/*/**");
    }
}
