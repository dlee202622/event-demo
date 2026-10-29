package com.example.demo.startup.annotations.feature;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * basic 档的产物：选择器返回 {@code basic} 时被导入的配置类。
 *
 * <p>定位：它存在的唯一意义是「被导入」——本模块没有任何地方 {@code @ComponentScan} 到它，
 * 也没有别处 {@code @Import} 它。如果 {@code @EnableStartupFeature} 的链路断了，
 * 容器里就不会有 {@code startupFeatureApiBasic} 这个 Bean，
 * 于是 {@code /api/startup/annotations/feature} 的 {@code residentBeanExists} 直接变 false，
 * 是一个自证式的设计。</p>
 *
 * <p>坑：配置类放在被扫描的包里也能生效（那时它是扫描产物，不是导入产物），
 * 两种来源在 {@code /api/startup/annotations/provenance} 里能区分出来；
 * 为了教学清晰，本 demo 特意用不同的 Bean 方法名，避免名字撞车。</p>
 */
@Configuration
public class StartupFeatureBasicConfig {

    /** basic 档不带属性绑定：boundNames 空、boundRetries 0，用来和 full 档对比 */
    @Bean
    public StartupFeatureApi startupFeatureApiBasic() {
        return new StartupFeatureApi("basic", List.of(), 0);
    }
}
