package com.yandu.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Publishes the versioned REST contract from the same controllers used at runtime. */
@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI yanduOpenApi() {
        return new OpenAPI()
                .info(new Info().title("言渡 YANDU API")
                        .version("1.0.0")
                        .description("""
                                Versioned project, catalog, translation, machine-translation, import, and release API.
                                Requires an OIDC access token as a Bearer token. Numeric BIGINT identifiers are
                                represented as decimal strings. Paginated endpoints use a 1-based page and
                                pageSize (default 50); limits are documented per operation. Errors use RFC 9457 Problem
                                Details; asynchronous jobs return 202 and expose durable status and retry endpoints.
                                """))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("OIDC access token issued for the configured YANDU API audience."))
                        .addSchemas("ApiProblem", problemSchema()))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    @Bean
    OpenApiCustomizer commonProblemResponses() {
        return openApi -> {
            openApi.getPaths().values().stream()
                    .flatMap(path -> path.readOperations().stream())
                    .forEach(this::addProblemResponses);
            normalizeIdentifierSchemas(openApi);
        };
    }

    private static void normalizeIdentifierSchemas(OpenAPI openApi) {
        openApi.getPaths().values().stream()
                .flatMap(path -> path.readOperations().stream())
                .filter(operation -> operation.getParameters() != null)
                .flatMap(operation -> operation.getParameters().stream())
                .filter(parameter -> parameter.getName() != null
                        && parameter.getName().matches("(?i)(id|.*id)")
                        && parameter.getIn() != null && parameter.getIn().equals("path"))
                .forEach(parameter -> parameter.setSchema(decimalStringSchema()));
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
            openApi.getComponents().getSchemas().values().forEach(schema -> normalizeProperties(schema, null));
        }
    }

    @SuppressWarnings("rawtypes")
    private static void normalizeProperties(Schema schema, String propertyName) {
        if (propertyName != null && propertyName.matches("(?i)(id|.*id)")) {
            if ("integer".equals(schema.getType())) {
                schema.setType("string");
                schema.setFormat(null);
                schema.setDescription("十进制字符串形式的 BIGINT ID");
            }
        }
        if (schema.getProperties() instanceof java.util.Map<?, ?> properties) {
            properties.forEach((name, property) -> {
                if (property instanceof Schema propertySchema) {
                    normalizeProperties(propertySchema, String.valueOf(name));
                }
            });
        }
        if (schema.getItems() instanceof Schema items) normalizeProperties(items, null);
    }

    private static Schema<?> decimalStringSchema() {
        return new StringSchema().description("十进制字符串形式的 BIGINT ID");
    }

    private void addProblemResponses(Operation operation) {
        operation.getResponses().addApiResponse("400", problemResponse("Invalid request or validation error."));
        operation.getResponses().addApiResponse("401", problemResponse("Missing or invalid OIDC access token."));
        operation.getResponses().addApiResponse("403", problemResponse("Project role does not permit this action."));
        operation.getResponses().addApiResponse("404", problemResponse("Project resource or task was not found."));
        operation.getResponses().addApiResponse("409", problemResponse("Version, idempotency, or task-state conflict."));
        operation.getResponses().addApiResponse("422", problemResponse("Translation quality or release gate failed."));
        operation.getResponses().addApiResponse("503", problemResponse("A required external provider is unavailable."));
    }

    private static ApiResponse problemResponse(String description) {
        return new ApiResponse().description(description)
                .content(new Content().addMediaType("application/problem+json",
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiProblem"))));
    }

    @SuppressWarnings("rawtypes")
    private static Schema problemSchema() {
        return new ObjectSchema()
                .addProperty("type", new StringSchema().format("uri"))
                .addProperty("title", new StringSchema())
                .addProperty("status", new IntegerSchema().format("int32"))
                .addProperty("detail", new StringSchema())
                .addProperty("instance", new StringSchema().format("uri-reference"))
                .addProperty("expectedVersion", new IntegerSchema().format("int64"))
                .addProperty("actualVersion", new IntegerSchema().format("int64"))
                .addProperty("expectedRevision", new IntegerSchema().format("int64"))
                .addProperty("actualRevision", new IntegerSchema().format("int64"))
                .addProperty("issues", new ArraySchema().items(new Schema<>().type("object")))
                .addProperty("failures", new ArraySchema().items(new StringSchema()));
    }
}
