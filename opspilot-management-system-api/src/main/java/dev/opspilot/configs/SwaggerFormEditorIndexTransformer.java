package dev.opspilot.configs;

import jakarta.servlet.http.HttpServletRequest;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.ui.SwaggerIndexPageTransformer;
import org.springdoc.webmvc.ui.SwaggerWelcomeCommon;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Adds the schema-driven OpsPilot request-body form plugin to springdoc's generated Swagger UI. */
@Component
@ConditionalOnProperty(prefix = "springdoc.swagger-ui", name = "enabled", havingValue = "true")
public class SwaggerFormEditorIndexTransformer extends SwaggerIndexPageTransformer {

    private static final String PLUGIN_SCRIPT = "./opspilot-form-editor.js";
    private static final String DOWNLOAD_PLUGIN = "SwaggerUIBundle.plugins.DownloadUrl";

    public SwaggerFormEditorIndexTransformer(SwaggerUiConfigProperties swaggerUiConfig,
                                             SwaggerUiOAuthProperties swaggerUiOAuth,
                                             SwaggerWelcomeCommon swaggerWelcome,
                                             ObjectMapperProvider objectMapperProvider) {
        super(swaggerUiConfig, swaggerUiOAuth, swaggerWelcome, objectMapperProvider);
    }

    @Override
    public Resource transform(HttpServletRequest request, Resource resource,
                              ResourceTransformerChain transformerChain) throws IOException {
        Resource transformed = super.transform(request, resource, transformerChain);
        String filename = resource.getFilename();
        if ("index.html".equals(filename)) {
            return replace(transformed, "<script src=\"./swagger-initializer.js\"",
                    "<script src=\"" + PLUGIN_SCRIPT + "\" charset=\"UTF-8\"></script>\n"
                            + "    <script src=\"./swagger-initializer.js\"");
        }
        if ("swagger-initializer.js".equals(filename)) {
            return replace(transformed, DOWNLOAD_PLUGIN, DOWNLOAD_PLUGIN + ",\n      OpsPilotFormEditorPlugin");
        }
        return transformed;
    }

    private static Resource replace(Resource resource, String target, String replacement) throws IOException {
        String source = resource.getContentAsString(StandardCharsets.UTF_8);
        if (!source.contains(target)) {
            return resource;
        }
        return new TransformedResource(resource, source.replace(target, replacement)
                .getBytes(StandardCharsets.UTF_8));
    }
}
