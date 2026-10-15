package com.picturesAI.generation_service.provider.huggingface;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class HuggingFaceProviderConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("demo"))
            .withUserConfiguration(TestConfig.class);

    @Test
    void beanCreatedWhenApiKeyPresent() {
        runner.withPropertyValues("huggingface.api-key=secret")
                .run(ctx -> assertThat(ctx).hasSingleBean(HuggingFaceProvider.class));
    }

    @Test
    void beanAbsentWhenApiKeyMissing() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(HuggingFaceProvider.class));
    }

    @Configuration
    @ComponentScan(basePackageClasses = HuggingFaceProvider.class)
    @EnableConfigurationProperties(HuggingFaceProperties.class)
    static class TestConfig {
    }
}