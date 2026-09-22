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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** A small HTTP client for the API of a running {@link Server}. */
final class ApiClient {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private final String base;

    ApiClient(Server server) {
        this("http://localhost:" + server.port());
    }

    /** A client for the server at a base URL such as {@code http://localhost:8081}. */
    ApiClient(String base) {
        this.base = base;
    }

    HttpResponse<String> get(String path) {
        return send(request(path).GET().build());
    }

    HttpResponse<String> post(String path, String body) {
        return send(request(path).POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    HttpResponse<String> put(String path, String body) {
        return send(request(path).PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    HttpResponse<String> put(String path) {
        return send(request(path).PUT(HttpRequest.BodyPublishers.noBody()).build());
    }

    HttpResponse<String> delete(String path) {
        return send(request(path).DELETE().build());
    }

    /** The body of a successful GET, as a tree. */
    JsonNode getJson(String path) {
        var response = get(path);
        if (response.statusCode() != 200) {
            throw new AssertionError("GET " + path + " returned " + response.statusCode() + ": " + response.body());
        }
        return json(response);
    }

    static JsonNode json(HttpResponse<String> response) {
        try {
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Opens the event stream. The events are read on a background thread into the returned queue
     * until the stream ends.
     */
    BlockingQueue<Event> events() {
        var queue = new LinkedBlockingQueue<Event>();
        var request = request("/api/v1/simulator/events").GET().build();
        HttpResponse<Stream<String>> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        var parser = new EventParser(queue);
        Thread.ofVirtual().name("sse-reader").start(() -> response.body().forEach(parser));
        return queue;
    }

    /** Parses the lines of an event stream; the events hold one {@code data} line each. */
    private static final class EventParser implements Consumer<String> {
        private final BlockingQueue<Event> queue;
        private String name;
        private String id;
        private String data;

        EventParser(BlockingQueue<Event> queue) {
            this.queue = queue;
        }

        @Override
        public void accept(String line) {
            if (line.isEmpty()) {
                if (name != null || data != null) {
                    queue.add(new Event(name, id, data == null ? "" : data));
                }
                name = null;
                id = null;
                data = null;
            } else if (line.startsWith(":")) {
                queue.add(new Event("comment", null, line.substring(1).trim()));
            } else if (line.startsWith("event: ")) {
                name = line.substring("event: ".length());
            } else if (line.startsWith("id: ")) {
                id = line.substring("id: ".length());
            } else if (line.startsWith("data: ")) {
                data = line.substring("data: ".length());
            }
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * One server-sent event, or a comment (with the name {@code comment} and the text as data).
     */
    record Event(String name, String id, String data) {

        JsonNode json() {
            try {
                return MAPPER.readTree(data);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
