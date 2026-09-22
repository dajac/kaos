package io.github.dajac.kaos.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import io.github.dajac.kaos.Simulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Serves the UI fixture of the test resources, {@code /static-ui}, which has the layout of a Vite
 * build: {@code index.html}, a file next to it and a hashed asset under {@code assets/}.
 */
class StaticUiTest {

    private static final String HTML = "text/html";

    private final HttpClient client = HttpClient.newHttpClient();
    private Server server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void theRootServesTheIndexWithoutCaching() {
        start("/static-ui");

        var response = get("/", HTML);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
            type -> assertThat(type).startsWith(HTML)
        );
        assertThat(response.headers().firstValue("Cache-Control")).hasValue(StaticUi.NO_CACHE);
        assertThat(response.body()).contains("<title>UI fixture</title>");
    }

    @Test
    void theIndexByNameIsNotCached() {
        start("/static-ui");

        var response = get("/index.html", HTML);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue(StaticUi.NO_CACHE);
        assertThat(response.body()).contains("<title>UI fixture</title>");
    }

    @Test
    void hashedAssetsAreImmutable() {
        start("/static-ui");

        var response = get("/assets/index-Ab12Cd34.js", "*/*");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue(StaticUi.IMMUTABLE);
        assertThat(response.body()).contains("console.log");
    }

    @Test
    void otherFilesAreNotCached() {
        start("/static-ui");

        var response = get("/robots.txt", "*/*");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue(StaticUi.NO_CACHE);
    }

    @Test
    void anUnknownPathThatAcceptsHtmlServesTheIndex() {
        start("/static-ui");

        var response = get("/groups/demo", HTML);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue(StaticUi.NO_CACHE);
        assertThat(response.body()).contains("<title>UI fixture</title>");
    }

    @Test
    void anUnknownApiPathIsAProblemEvenForABrowser() {
        start("/static-ui");

        for (var path : new String[] {"/api", "/api/v1/nothing", "/api/v2/meta"}) {
            var response = get(path, HTML);

            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(response.headers().firstValue("Content-Type")).as(path).hasValue(ProblemHandler.CONTENT_TYPE);
        }
    }

    @Test
    void theApiIsStillServed() {
        start("/static-ui");

        var response = get("/api/v1/meta", "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
    }

    @Test
    void withoutTheUiTheRootIsNotServed() {
        var ui = new StaticUi("/no-such-ui");
        assertThat(ui.present()).isFalse();
        start("/no-such-ui");

        var response = get("/", HTML);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(ProblemHandler.CONTENT_TYPE);
    }

    private void start(String directory) {
        server = new Server(new Simulator("localhost:9092"), SseHub.PING_INTERVAL, new StaticUi(directory)).start(0);
    }

    private HttpResponse<String> get(String path, String accept) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
            .header("Accept", accept)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AssertionError("GET " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("GET " + path + " was interrupted", e);
        }
    }
}
