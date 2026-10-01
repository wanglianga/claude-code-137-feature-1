package com.community.medrecycle.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 3.x 文档：访问 /swagger-ui.html 或 /v3/api-docs */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI medRecycleOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("社区过期药品回收称重兑换与环保交接服务 API")
                        .version("1.1.0")
                        .description("预约、登记称重、隐私包装处理、人工复核、积分、暂存柜、环保交接与批次追溯")
                        .license(new License().name("内部演示")))
                .components(new Components().addSecuritySchemes("X-Auth-Token",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Auth-Token")));
    }
}
