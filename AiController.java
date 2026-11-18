package com.emotune.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Endpoints seguros para usar Google Gemini desde EmoGuía. La API key nunca vuelve al navegador:
 * se guarda cifrada (AES-GCM) y solo se envía a Google desde el servidor.
 *
 * Variables de entorno: EMOTUNE_SECRET_KEY (cifrado de claves guardadas), GEMINI_API_KEY (opcional, clave del servidor),
 * GEMINI_BASE_URL (opcional, solo para pruebas).
 */
@RestController
public class AiController {
    private final AiService service;

    public AiController(JdbcTemplate jdbc, ObjectMapper mapper, Environment env) {
        String secret = env.getProperty("EMOTUNE_SECRET_KEY", "").trim();
        SecretBox box = secret.length() >= 16 ? new SecretBox(secret) : null;
        GeminiClient client = new GeminiClient(mapper, env.getProperty("GEMINI_BASE_URL", ""));
        this.service = new AiService(client, box, env.getProperty("GEMINI_API_KEY", ""), new JdbcStore(jdbc));
    }

    private static final class JdbcStore implements AiService.Store {
        private final JdbcTemplate jdbc;
        private volatile boolean ready = false;
        JdbcStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        @Override public void ensureSchema() {
            if (ready) return;
            synchronized (this) {
                if (ready) return;
                jdbc.execute("CREATE TABLE IF NOT EXISTS emotune_user_ai (google_sub VARCHAR(255) PRIMARY KEY, provider VARCHAR(40) NOT NULL, model VARCHAR(100) NOT NULL, api_key_enc TEXT NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
                ready = true;
            }
        }

        @Override public Optional<AiService.Row> find(String sub) {
            List<AiService.Row> rows = jdbc.query("SELECT provider, model, api_key_enc FROM emotune_user_ai WHERE google_sub = ?",
                (rs, n) -> new AiService.Row(rs.getString(1), rs.getString(2), rs.getString(3)), sub);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        }

        @Override public void save(String sub, String provider, String model, String keyEnc) {
            jdbc.update("INSERT INTO emotune_user_ai (google_sub, provider, model, api_key_enc, updated_at) VALUES (?, ?, ?, ?, NOW()) ON CONFLICT (google_sub) DO UPDATE SET provider = EXCLUDED.provider, model = EXCLUDED.model, api_key_enc = EXCLUDED.api_key_enc, updated_at = NOW()",
                sub, provider, model, keyEnc);
        }

        @Override public void delete(String sub) {
            jdbc.update("DELETE FROM emotune_user_ai WHERE google_sub = ?", sub);
        }
    }

    @GetMapping(value = "/api/ai/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getConfig(Authentication auth) {
        String sub = subject(auth);
        if (sub == null) return unauthorized();
        try {
            return ResponseEntity.ok(service.getConfig(sub));
        } catch (AiException e) {
            return fail(e);
        }
    }

    @PutMapping(value = "/api/ai/config", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> saveConfig(@RequestBody JsonNode body, Authentication auth, HttpServletRequest request) {
        String sub = subject(auth);
        if (sub == null) return unauthorized();
        if (!OriginCheck.valid(request)) return forbidden();
        try {
            return ResponseEntity.ok(service.saveConfig(sub, text(body, "provider"), text(body, "model"), text(body, "apiKey")));
        } catch (AiException e) {
            return fail(e);
        }
    }

    @DeleteMapping(value = "/api/ai/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteConfig(Authentication auth, HttpServletRequest request) {
        String sub = subject(auth);
        if (sub == null) return unauthorized();
        if (!OriginCheck.valid(request)) return forbidden();
        try {
            service.deleteConfig(sub);
            return ResponseEntity.ok(service.getConfig(sub));
        } catch (AiException e) {
            return fail(e);
        }
    }

    @PostMapping(value = "/api/ai/test", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> test(@RequestBody JsonNode body, Authentication auth, HttpServletRequest request) {
        String sub = subject(auth);
        if (sub == null) return unauthorized();
        if (!OriginCheck.valid(request)) return forbidden();
        try {
            String reply = service.test(sub, text(body, "apiKey"), text(body, "model"));
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("reply", reply);
            return ResponseEntity.ok(ok);
        } catch (AiException e) {
            return fail(e);
        }
    }

    @PostMapping(value = "/api/ai/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> chat(@RequestBody JsonNode body, Authentication auth, HttpServletRequest request) {
        String sub = subject(auth);
        if (sub == null) return unauthorized();
        if (!OriginCheck.valid(request)) return forbidden();
        try {
            List<AiService.ChatMsg> msgs = new ArrayList<>();
            JsonNode arr = body.path("messages");
            if (arr.isArray()) for (JsonNode m : arr) msgs.add(new AiService.ChatMsg(m.path("role").asText("user"), m.path("content").asText("")));
            String reply = service.chat(sub, msgs, text(body, "context"));
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("reply", reply);
            return ResponseEntity.ok(ok);
        } catch (AiException e) {
            return fail(e);
        }
    }

    private static String text(JsonNode body, String field) {
        return body != null && body.hasNonNull(field) ? body.get(field).asText("") : "";
    }

    private static String subject(Authentication auth) {
        return auth != null && auth.getPrincipal() instanceof OidcUser user ? user.getSubject() : null;
    }

    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("ok", false, "error", Map.of("code", "UNAUTHENTICATED", "message", "Inicia sesión con Google.")));
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("ok", false, "error", Map.of("code", "ORIGIN", "message", "Origen no permitido.")));
    }

    private static ResponseEntity<?> fail(AiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("ok", false, "error", Map.of("code", e.code(), "message", e.getMessage())));
    }

    /** Misma comprobación de origen que usa CloudStateController. */
    static final class OriginCheck {
        static boolean valid(HttpServletRequest request) {
            String origin = request.getHeader("Origin");
            if (origin == null || origin.isBlank()) return false;
            String fh = request.getHeader("X-Forwarded-Host");
            String host = fh != null && !fh.isBlank() ? fh.split(",")[0].trim() : request.getHeader("Host");
            String fp = request.getHeader("X-Forwarded-Proto");
            String scheme = fp != null && !fp.isBlank() ? fp.split(",")[0].trim() : request.getScheme();
            return host != null && origin.equalsIgnoreCase(scheme + "://" + host);
        }
    }
}
