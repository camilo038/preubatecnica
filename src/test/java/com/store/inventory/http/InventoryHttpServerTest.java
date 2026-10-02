package com.store.inventory.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.Inventory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InventoryHttpServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private InventoryHttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = new InventoryHttpServer(Inventory.create(Clock.systemUTC(), (sku, units) -> { }), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void fullFlow() throws Exception {
        assertEquals(201, post("/products", "{\"sku\":\"TV\",\"category\":\"FLASH_SALE\"}").statusCode());
        assertEquals(200, post("/products/TV/stock", "{\"quantity\":10}").statusCode());
        assertEquals(201, post("/reservations", "{\"orderId\":\"O-1\",\"sku\":\"TV\",\"quantity\":2}").statusCode());
        assertEquals(422, post("/reservations", "{\"orderId\":\"O-2\",\"sku\":\"TV\",\"quantity\":3}").statusCode());
        assertEquals(200, post("/reservations/O-1/confirm", "").statusCode());
        assertEquals(409, post("/reservations/O-1/confirm", "").statusCode());

        HttpResponse<String> available = get("/products/TV/available");
        assertTrue(available.body().contains("\"available\":8"), available.body());
    }

    @Test
    void badBodyIsBadRequest() throws Exception {
        assertEquals(400, post("/products", "{\"sku\":\"TV\",\"category\":\"NOPE\"}").statusCode());
        assertEquals(400, post("/products", "no es json").statusCode());
    }

    @Test
    void servesSwagger() throws Exception {
        assertEquals(200, get("/swagger").statusCode());
        assertTrue(get("/openapi.yaml").body().contains("/reservations/{orderId}/confirm"));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + server.port() + path);
    }
}
