package com.emotune.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Pruebas contra un servidor HTTP local que imita la API de Gemini (no gastan cuota ni necesitan clave real). */
class GeminiIntegrationTest {
    private static final ObjectMapper OM = new ObjectMapper();
    private HttpServer server;
    private String base;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastKey = new AtomicReference<>();
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private volatile String response = "";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            lastPath.set(ex.getRequestURI().getPath());
            lastKey.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            lastBody.set(OM.readTree(ex.getRequestBody().readAllBytes()));
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
    }

    @AfterEach
    void stop() { server.stop(0); }

    private static String ok(String text) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":" + quote(text) + "}]},\"finishReason\":\"STOP\"}]}";
    }
    private static String quote(String s) { try { return OM.writeValueAsString(s); } catch (Exception e) { throw new RuntimeException(e); } }

    private GeminiClient client() { return new GeminiClient(OM, base); }

    @Test
    void sendsOfficialRequestAndParsesText() throws Exception {
        response = ok("Hola, ¿cómo te sientes hoy?");
        String r = client().generate("AIzaTESTKEY1234567890", "gemini-2.5-flash", "SISTEMA",
            List.of(new GeminiClient.Msg("assistant", "ignorado"), new GeminiClient.Msg("user", "hola"),
                    new GeminiClient.Msg("user", "estoy cansado"), new GeminiClient.Msg("assistant", "cuéntame"),
                    new GeminiClient.Msg("user", "mal día")));
        assertEquals("Hola, ¿cómo te sientes hoy?", r);
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", lastPath.get());
        assertEquals("AIzaTESTKEY1234567890", lastKey.get());
        JsonNode b = lastBody.get();
        assertEquals("SISTEMA", b.at("/systemInstruction/parts/0/text").asText());
        assertEquals(3, b.get("contents").size());
        assertEquals("user", b.at("/contents/0/role").asText());
        assertEquals("hola\nestoy cansado", b.at("/contents/0/parts/0/text").asText());
        assertEquals("model", b.at("/contents/1/role").asText());
        assertEquals("user", b.at("/contents/2/role").asText());
    }

    @Test
    void skipsThoughtPartsAndJoinsText() throws Exception {
        response = "{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"pienso\"},{\"text\":\"A\"},{\"text\":\"B\"}]}}]}";
        assertEquals("AB", client().generate("k", "", null, List.of(new GeminiClient.Msg("user", "x"))));
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", lastPath.get());
    }

    private AiException failure(int st, String body) {
        status = st; response = body;
        return assertThrows(AiException.class, () -> client().generate("k", "gemini-2.5-flash", "s", List.of(new GeminiClient.Msg("user", "x"))));
    }

    @Test
    void mapsHttpErrors() {
        assertEquals("INVALID_KEY", failure(400, "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\",\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}").code());
        assertEquals("FORBIDDEN", failure(403, "{\"error\":{\"message\":\"denied\"}}").code());
        assertEquals("MODEL_NOT_FOUND", failure(404, "{\"error\":{\"message\":\"not found\"}}").code());
        AiException rl = failure(429, "{\"error\":{\"message\":\"quota\"}}");
        assertEquals("RATE_LIMIT", rl.code());
        assertEquals(429, rl.status());
        assertEquals("BAD_REQUEST", failure(400, "{\"error\":{\"message\":\"mal\"}}").code());
        hits.set(0);
        assertEquals("UNAVAILABLE", failure(503, "{}").code());
        assertEquals(2, hits.get(), "debe reintentar una vez en 503");
    }

    @Test
    void handlesEmptyAndBlockedResponses() {
        assertEquals("EMPTY", failure(200, "{\"candidates\":[]}").code());
        assertEquals("EMPTY", failure(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"  \"}]},\"finishReason\":\"STOP\"}]}").code());
        assertEquals("BLOCKED", failure(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}").code());
        assertEquals("BLOCKED", failure(200, "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}").code());
        assertEquals("BAD_RESPONSE", failure(200, "no es json").code());
    }

    @Test
    void reportsNetworkFailure() {
        server.stop(0);
        AiException e = assertThrows(AiException.class, () -> client().generate("k", "gemini-2.5-flash", "s", List.of(new GeminiClient.Msg("user", "x"))));
        assertEquals("NETWORK", e.code());
    }

    @Test
    void rejectsBadModelNames() {
        assertThrows(AiException.class, () -> GeminiClient.normalizeModel("../../v1/files"));
        assertThrows(AiException.class, () -> GeminiClient.normalizeModel("a b"));
        assertDoesNotThrow(() -> assertEquals("gemini-2.5-flash", GeminiClient.normalizeModel(" models/gemini-2.5-flash ")));
        assertDoesNotThrow(() -> assertEquals("gemini-2.5-flash", GeminiClient.normalizeModel("")));
    }

    @Test
    void secretBoxRoundTrip() {
        SecretBox a = new SecretBox("clave-maestra-de-prueba-123");
        String enc = a.encrypt("AIzaSecretValue1234567890");
        assertFalse(enc.contains("AIza"));
        assertEquals("AIzaSecretValue1234567890", a.decrypt(enc));
        assertNotEquals(enc, a.encrypt("AIzaSecretValue1234567890"), "IV aleatorio");
        assertNull(new SecretBox("otra-clave-maestra-distinta").decrypt(enc));
        assertNull(a.decrypt("basura"));
    }

    static class MemStore implements AiService.Store {
        final Map<String, AiService.Row> rows = new ConcurrentHashMap<>();
        public void ensureSchema() {}
        public Optional<AiService.Row> find(String sub) { return Optional.ofNullable(rows.get(sub)); }
        public void save(String sub, String p, String m, String k) { rows.put(sub, new AiService.Row(p, m, k)); }
        public void delete(String sub) { rows.remove(sub); }
    }

    @Test
    void serviceFlow() throws Exception {
        MemStore store = new MemStore();
        AiService svc = new AiService(client(), new SecretBox("clave-maestra-de-prueba-123"), null, store);
        String key = "AIzaSyD-TestKey_1234567890abcdef";

        assertFalse(svc.getConfig("u1").configured());
        assertEquals("NO_KEY", assertThrows(AiException.class, () -> svc.chat("u1", List.of(new AiService.ChatMsg("user", "hola")), "")).code());
        assertEquals("NO_KEY", assertThrows(AiException.class, () -> svc.saveConfig("u1", "gemini", "gemini-2.5-flash", "  ")).code());
        assertEquals("BAD_KEY_FORMAT", assertThrows(AiException.class, () -> svc.saveConfig("u1", "gemini", "gemini-2.5-flash", "tiene espacios 123456789")).code());
        assertEquals("BAD_PROVIDER", assertThrows(AiException.class, () -> svc.saveConfig("u1", "openai", "x", key)).code());

        AiService.ConfigView v = svc.saveConfig("u1", "gemini", "gemini-2.5-flash", key);
        assertTrue(v.configured());
        assertEquals("user", v.source());
        assertEquals("AIzaSyD-TestKey_1234567890abcdef".substring(0, 4) + "••••cdef", v.keyHint());
        assertFalse(OM.writeValueAsString(v).contains(key), "la clave completa nunca sale en la config");
        assertFalse(store.rows.get("u1").keyEnc().contains("AIza"), "se guarda cifrada");

        // cambiar solo el modelo conservando la clave
        v = svc.saveConfig("u1", "gemini", "gemini-2.5-flash-lite", "");
        assertEquals("gemini-2.5-flash-lite", v.model());
        assertEquals("cdef", v.keyHint().substring(v.keyHint().length() - 4));

        response = ok("Entiendo, vamos con algo suave. [[REC: Luna]]");
        String reply = svc.chat("u1", List.of(new AiService.ChatMsg("user", "estoy triste")), "Biblioteca:\nLuna — X [Tristeza]");
        assertTrue(reply.contains("[[REC: Luna]]"));
        assertEquals(key, lastKey.get());
        assertEquals("/v1beta/models/gemini-2.5-flash-lite:generateContent", lastPath.get());
        String sys = lastBody.get().at("/systemInstruction/parts/0/text").asText();
        assertTrue(sys.contains("EmoGuía") && sys.contains("Luna — X [Tristeza]"));

        // probar con una clave aún sin guardar
        response = ok("listo");
        assertEquals("listo", svc.test("u1", "AIzaOtraClaveDistinta123456", "gemini-2.5-flash"));
        assertEquals("AIzaOtraClaveDistinta123456", lastKey.get());

        svc.deleteConfig("u1");
        assertFalse(svc.getConfig("u1").configured());
    }

    @Test
    void serverKeyFallbackAndNoSecretBox() throws Exception {
        AiService svc = new AiService(client(), null, "AIzaServerKey1234567890", new MemStore());
        assertTrue(svc.getConfig("u2").configured());
        assertEquals("server", svc.getConfig("u2").source());
        response = ok("hola");
        assertEquals("hola", svc.chat("u2", List.of(new AiService.ChatMsg("user", "hola")), null));
        assertEquals("AIzaServerKey1234567890", lastKey.get());
        assertEquals("STORAGE_DISABLED", assertThrows(AiException.class, () -> svc.saveConfig("u2", "gemini", "", "AIzaUnaClaveValida123456")).code());
    }

    @Test
    void rateLimitsChat() throws Exception {
        AiService svc = new AiService(client(), null, "AIzaServerKey1234567890", new MemStore());
        response = ok("ok");
        for (int i = 0; i < 20; i++) svc.chat("u3", List.of(new AiService.ChatMsg("user", "x")), "");
        assertEquals("TOO_MANY", assertThrows(AiException.class, () -> svc.chat("u3", List.of(new AiService.ChatMsg("user", "x")), "")).code());
        svc.chat("u4", List.of(new AiService.ChatMsg("user", "x")), "");
    }
}
