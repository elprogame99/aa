package com.emotune.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Cliente de la API oficial de Google Gemini (REST):
 * POST {base}/models/{MODEL}:generateContent con la cabecera x-goog-api-key.
 */
public class GeminiClient {
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    public static final String DEFAULT_MODEL = "gemini-2.5-flash";
    private static final Pattern MODEL_OK = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    public record Msg(String role, String text) {}

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public GeminiClient(ObjectMapper mapper, String baseUrl) {
        this.mapper = mapper;
        String b = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        this.baseUrl = b.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Valida el nombre del modelo (evita inyectar rutas en la URL) y aplica el valor predeterminado. */
    public static String normalizeModel(String model) throws AiException {
        String m = model == null ? "" : model.trim();
        if (m.startsWith("models/")) m = m.substring(7);
        if (m.isEmpty()) return DEFAULT_MODEL;
        if (!MODEL_OK.matcher(m).matches())
            throw new AiException(400, "BAD_MODEL", "El nombre del modelo no es válido. Ejemplo: " + DEFAULT_MODEL);
        return m;
    }

    public String generate(String apiKey, String model, String system, List<Msg> history) throws AiException {
        String m = normalizeModel(model);
        String payload;
        try {
            payload = mapper.writeValueAsString(buildBody(system, history));
        } catch (Exception e) {
            throw new AiException(500, "INTERNAL", "No se pudo preparar la petición a Gemini.");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/models/" + m + ":generateContent"))
            .timeout(Duration.ofSeconds(45))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        HttpResponse<String> res = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                res = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException e) {
                throw new AiException(504, "TIMEOUT", "Gemini tardó demasiado en responder. Inténtalo de nuevo.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiException(502, "NETWORK", "Se interrumpió la conexión con Gemini.");
            } catch (IOException e) {
                throw new AiException(502, "NETWORK", "No se pudo conectar con Gemini. Revisa la conexión a internet del servidor.");
            }
            if (res.statusCode() == 503 && attempt == 0) {
                try { Thread.sleep(900); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                continue;
            }
            break;
        }
        if (res.statusCode() / 100 != 2) throw mapHttpError(res.statusCode(), res.body(), m);
        return parseReply(res.body());
    }

    ObjectNode buildBody(String system, List<Msg> history) {
        ObjectNode root = mapper.createObjectNode();
        if (system != null && !system.isBlank()) {
            root.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        }
        // Gemini exige que la conversación empiece por «user»; se fusionan turnos consecutivos del mismo rol.
        List<String[]> turns = new ArrayList<>();
        for (Msg msg : history) {
            if (msg == null || msg.text() == null || msg.text().isBlank()) continue;
            String role = "assistant".equals(msg.role()) || "model".equals(msg.role()) ? "model" : "user";
            if (turns.isEmpty() && role.equals("model")) continue;
            if (!turns.isEmpty() && turns.get(turns.size() - 1)[0].equals(role)) {
                turns.get(turns.size() - 1)[1] += "\n" + msg.text();
            } else {
                turns.add(new String[]{role, msg.text()});
            }
        }
        ArrayNode contents = root.putArray("contents");
        for (String[] t : turns) {
            ObjectNode c = contents.addObject();
            c.put("role", t[0]);
            c.putArray("parts").addObject().put("text", t[1]);
        }
        ObjectNode gen = root.putObject("generationConfig");
        gen.put("temperature", 0.9);
        gen.put("maxOutputTokens", 2048);
        return root;
    }

    String parseReply(String body) throws AiException {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new AiException(502, "BAD_RESPONSE", "Gemini devolvió una respuesta que no se pudo leer.");
        }
        String block = root.path("promptFeedback").path("blockReason").asText("");
        JsonNode cands = root.path("candidates");
        if (!cands.isArray() || cands.isEmpty()) {
            if (!block.isEmpty())
                throw new AiException(422, "BLOCKED", "Gemini no pudo responder a ese mensaje por sus filtros de seguridad. Prueba a reformularlo.");
            throw new AiException(502, "EMPTY", "Gemini no devolvió ninguna respuesta. Inténtalo de nuevo.");
        }
        JsonNode cand = cands.get(0);
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : cand.path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) continue;
            if (part.hasNonNull("text")) sb.append(part.get("text").asText());
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            String fin = cand.path("finishReason").asText("");
            if (fin.equals("SAFETY") || fin.equals("PROHIBITED_CONTENT") || fin.equals("BLOCKLIST") || !block.isEmpty())
                throw new AiException(422, "BLOCKED", "Gemini no pudo responder a ese mensaje por sus filtros de seguridad. Prueba a reformularlo.");
            throw new AiException(502, "EMPTY", "Gemini devolvió una respuesta vacía. Inténtalo de nuevo.");
        }
        return text;
    }

    AiException mapHttpError(int status, String body, String model) {
        String msg = "", reason = "";
        try {
            JsonNode err = mapper.readTree(body).path("error");
            msg = err.path("message").asText("");
            for (JsonNode d : err.path("details")) if (d.hasNonNull("reason")) reason = d.get("reason").asText();
        } catch (Exception ignored) { }
        boolean badKey = reason.equals("API_KEY_INVALID") || msg.toLowerCase().contains("api key not valid")
            || msg.toLowerCase().contains("api key expired");
        if (badKey || status == 401)
            return new AiException(400, "INVALID_KEY", "La API key no es válida o ha caducado. Revísala en Google AI Studio.");
        if (status == 403)
            return new AiException(400, "FORBIDDEN", "Esta clave no tiene permiso para usar la API de Gemini (o el servicio no está habilitado en su proyecto).");
        if (status == 404)
            return new AiException(400, "MODEL_NOT_FOUND", "El modelo «" + model + "» no existe o no está disponible para tu clave.");
        if (status == 429)
            return new AiException(429, "RATE_LIMIT", "Has alcanzado el límite de uso de Gemini (cuota o peticiones por minuto). Espera un momento o revisa tu cuota.");
        if (status == 400)
            return new AiException(400, "BAD_REQUEST", "Gemini rechazó la petición" + (msg.isEmpty() ? "." : ": " + trunc(msg, 180)));
        if (status >= 500)
            return new AiException(503, "UNAVAILABLE", "El servicio de Gemini no está disponible ahora mismo. Inténtalo en unos minutos.");
        return new AiException(502, "HTTP_" + status, "Gemini respondió con un error inesperado (HTTP " + status + ").");
    }

    private static String trunc(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }
}
