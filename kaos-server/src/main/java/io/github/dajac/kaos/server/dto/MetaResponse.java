package io.github.dajac.kaos.server.dto;

/**
 * The body of {@code GET /api/v1/meta}.
 *
 * @param version the version of the running build
 */
public record MetaResponse(String version) {}
