package io.testkit.spring;

import io.testkit.core.TestKit;
import io.testkit.core.TestKitConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot autoconfiguration for TestKit.
 * Add {@code io.testkit.spring.TestKitAutoConfiguration} to your
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} file.
 */
@AutoConfiguration
@EnableConfigurationProperties(TestKitProperties.class)
public class TestKitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TestKitConfig testKitConfig(TestKitProperties props) {
        return TestKitConfig.builder()
                .baseUrl(props.getBaseUrl())
                .timeout(props.getTimeout())
                .failFast(props.isFailFast())
                .verbose(props.isVerbose())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public TestKitFactory testKitFactory(TestKitConfig config) {
        return new TestKitFactory(config);
    }
}
