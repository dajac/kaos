package io.github.dajac.kaos.server.dto;

import java.util.List;
import java.util.Map;

import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.Protocol;

/**
 * The body of {@code GET /api/v1/meta}: the build version and the {@link ConfigMetadata} table, with
 * the defaults, the ranges, the name rule, the assignors of each protocol and the faults.
 *
 * @param version the version of the running build
 * @param fields see {@link ConfigMetadata#fields()}
 * @param nameRule see {@link ConfigMetadata#nameRule()}
 * @param assignors see {@link ConfigMetadata#assignors()}
 * @param faults see {@link ConfigMetadata#faults()}
 */
public record MetaResponse(
    String version,
    List<ConfigMetadata.Field> fields,
    ConfigMetadata.NameRule nameRule,
    Map<Protocol, ConfigMetadata.AssignorOptions> assignors,
    List<String> faults
) {

    /** The metadata of the given build version. */
    public static MetaResponse of(String version, ConfigMetadata metadata) {
        return new MetaResponse(version, metadata.fields(), metadata.nameRule(), metadata.assignors(),
            metadata.faults());
    }
}
