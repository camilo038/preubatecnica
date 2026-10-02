package com.store.inventory.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.store.inventory.Inventory;
import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InventoryHttpServer {

    private static final System.Logger LOG = System.getLogger(InventoryHttpServer.class.getName());

    private static final Pattern STOCK = Pattern.compile("^/products/([^/]+)/stock$");
    private static final Pattern AVAILABLE = Pattern.compile("^/products/([^/]+)/available$");
    private static final Pattern CONFIRM = Pattern.compile("^/reservations/([^/]+)/confirm$");

    private final InventoryService service;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final byte[] openApi = resource("openapi.yaml");
    private final byte[] swaggerPage = resource("swagger.html");

    public InventoryHttpServer(InventoryService service, int port) throws IOException {
        this.service = service;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        InventoryService service = Inventory.create(Clock.systemUTC(),
                (sku, units) -> System.out.println("[aviso compras] " + sku + " quedan " + units + " unidades"));
        InventoryHttpServer app = new InventoryHttpServer(service, port);
        app.start();
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
        System.out.println("Inventario escuchando en http://localhost:" + app.port() + "/swagger");
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdown();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                route(exchange);
            } catch (OrderLimitExceededException e) {
                sendJson(exchange, 422, error(e));
            } catch (InsufficientStockException | IllegalStateException e) {
                sendJson(exchange, 409, error(e));
            } catch (IllegalArgumentException | JsonProcessingException e) {
                sendJson(exchange, 400, error(e));
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Unexpected error on " + exchange.getRequestURI(), e);
                sendJson(exchange, 500, Map.of("error", "Internal error"));
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        Matcher m;
        if (method.equals("GET") && path.equals("/openapi.yaml")) {
            send(exchange, 200, "application/yaml", openApi);
        } else if (method.equals("GET") && (path.equals("/swagger") || path.equals("/"))) {
            send(exchange, 200, "text/html; charset=utf-8", swaggerPage);
        } else if (method.equals("POST") && path.equals("/products")) {
            JsonNode body = body(exchange);
            String sku = text(body, "sku");
            service.registerProduct(sku, ProductCategory.valueOf(text(body, "category")));
            sendJson(exchange, 201, Map.of("sku", sku));
        } else if (method.equals("POST") && (m = STOCK.matcher(path)).matches()) {
            String sku = m.group(1);
            service.addStock(sku, number(body(exchange), "quantity"));
            sendJson(exchange, 200, Map.of("sku", sku, "available", service.available(sku)));
        } else if (method.equals("GET") && (m = AVAILABLE.matcher(path)).matches()) {
            sendJson(exchange, 200, Map.of("sku", m.group(1), "available", service.available(m.group(1))));
        } else if (method.equals("POST") && path.equals("/reservations")) {
            JsonNode body = body(exchange);
            Reservation r = service.reserve(text(body, "orderId"), text(body, "sku"), number(body, "quantity"));
            sendJson(exchange, 201, Map.of("orderId", r.orderId(), "sku", r.sku(),
                    "quantity", r.quantity(), "expiresAt", r.expiresAt().toString()));
        } else if (method.equals("POST") && (m = CONFIRM.matcher(path)).matches()) {
            service.confirm(m.group(1));
            sendJson(exchange, 200, Map.of("orderId", m.group(1), "status", "CONFIRMED"));
        } else {
            sendJson(exchange, 404, Map.of("error", "Not found"));
        }
    }

    private JsonNode body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            JsonNode node = json.readTree(in);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("A JSON object body is required");
            }
            return node;
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText();
    }

    private static int number(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.asInt();
    }

    private static Map<String, String> error(Exception e) {
        return Map.of("error", String.valueOf(e.getMessage()));
    }

    private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        send(exchange, status, "application/json", json.writeValueAsBytes(payload));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static byte[] resource(String name) {
        try (InputStream in = InventoryHttpServer.class.getResourceAsStream("/" + name)) {
            return Objects.requireNonNull(in, name + " not found").readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
