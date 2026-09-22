/*
 * Copyright 2026 The Kaos Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dajac.kaos.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import io.javalin.config.JavalinConfig;
import io.javalin.http.ContentType;
import io.javalin.http.Context;
import io.javalin.http.Header;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.staticfiles.Location;

/**
 * Serves the UI, the classpath directory {@code /ui} that the {@code kaos-ui} jar holds, at {@code /}.
 *
 * <ul>
 *   <li>The hashed assets of the Vite build, under {@code /assets}, are cached forever
 *       ({@code immutable}).</li>
 *   <li>Every other file, {@code index.html} included, is revalidated on every use
 *       ({@code no-cache}), so that a new build is picked up at once.</li>
 *   <li>Any other {@code GET} that accepts HTML, outside {@code /api} and {@code /assets}, answers
 *       {@code index.html}, so that the UI can use its own paths (SPA fallback). A missing asset,
 *       such as a chunk of a previous build, is a 404.</li>
 * </ul>
 *
 * <p>A build with {@code -DskipUi} has no UI: then nothing is served, and {@code /} is a 404.
 */
final class StaticUi {

    static final String DIRECTORY = "/ui";
    static final String ASSETS = "/assets";
    static final String IMMUTABLE = "public, max-age=31536000, immutable";
    static final String NO_CACHE = "no-cache";

    private final String directory;

    StaticUi() {
        this(DIRECTORY);
    }

    /**
     * @param directory the classpath directory of the UI, with a leading slash
     */
    StaticUi(String directory) {
        this.directory = directory;
    }

    /** Whether the classpath holds the UI. */
    boolean present() {
        return StaticUi.class.getResource(index()) != null;
    }

    /** Registers the static files and the SPA fallback, if the classpath holds the UI. */
    void register(JavalinConfig config) {
        if (!present()) {
            return;
        }
        // The first location that has the file serves it, so the assets get their own headers.
        config.staticFiles.add(files -> {
            files.hostedPath = ASSETS;
            files.directory = directory + ASSETS;
            files.location = Location.CLASSPATH;
            files.headers = Map.of(Header.CACHE_CONTROL, IMMUTABLE);
        });
        config.staticFiles.add(files -> {
            files.hostedPath = "/";
            files.directory = directory;
            files.location = Location.CLASSPATH;
            files.headers = Map.of(Header.CACHE_CONTROL, NO_CACHE);
        });
        config.spaRoot.addHandler("/", this::fallback);
    }

    private void fallback(Context ctx) {
        if (under(ctx.path(), "/api") || under(ctx.path(), ASSETS)) {
            throw new NotFoundResponse("No resource at " + ctx.path());
        }
        try (var in = StaticUi.class.getResourceAsStream(index())) {
            if (in == null) {
                throw new NotFoundResponse("No resource at " + ctx.path());
            }
            ctx.header(Header.CACHE_CONTROL, NO_CACHE);
            ctx.contentType(ContentType.TEXT_HTML);
            ctx.result(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private String index() {
        return directory + "/index.html";
    }
}
