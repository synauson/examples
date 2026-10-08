package com.synauson.testbed.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * SPA fallback for the Vite-built React app.
 *
 * <p>The frontend uses client-side routing: {@code /} redirects, and
 * {@code /room/<id>} renders the lobby/call experience. Only
 * {@code index.html} and the {@code assets/*} bundle physically exist
 * on the classpath, so we fall through to {@code index.html} for any
 * virtual path. Real 404s on legitimately-missing assets still surface
 * because we exclude paths with file extensions and reserved prefixes.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaPathResolver());
    }

    private static class SpaPathResolver extends PathResourceResolver {
        private static final Resource INDEX = new ClassPathResource("/static/index.html");

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource direct = super.getResource(resourcePath, location);
            if (direct != null) return direct;
            if (looksLikeAsset(resourcePath)) return null;
            return INDEX.exists() ? INDEX : null;
        }

        private boolean looksLikeAsset(String path) {
            return path.contains(".") || path.startsWith("ws/")
                || path.startsWith("actuator/") || path.startsWith("api/");
        }
    }
}
