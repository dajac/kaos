package io.github.dajac.kaos.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private Server server;

    @BeforeEach
    void startServer() {
        server = new Server().start(0);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void metaReturnsTheBuildVersion() throws Exception {
        HttpResponse<String> response = get("/api/v1/meta");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");

        JsonNode body = new ObjectMapper().readTree(response.body());
        assertThat(body.fieldNames()).toIterable().containsExactly("version");
        // The version comes from the filtered resource, so the Maven placeholder must be resolved.
        assertThat(body.get("version").asText()).isEqualTo(Version.read()).matches("\\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    void unknownApiPathReturns404() throws Exception {
        assertThat(get("/api/v1/unknown").statusCode()).isEqualTo(404);
    }

    @Test
    void closeIsIdempotent() {
        server.close();
        server.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
