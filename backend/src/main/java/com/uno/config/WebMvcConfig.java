package com.uno.config;

import com.uno.auth.ActiveLoginHttpInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ActiveLoginHttpInterceptor activeLoginHttpInterceptor;

    public WebMvcConfig(ActiveLoginHttpInterceptor activeLoginHttpInterceptor) {
        this.activeLoginHttpInterceptor = activeLoginHttpInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(activeLoginHttpInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/user/login", "/api/user/register", "/api/ws/**");
    }
}
