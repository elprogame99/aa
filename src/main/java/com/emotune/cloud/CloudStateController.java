package com.emotune.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class CloudStateController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private volatile boolean schemaReady = false;

    public CloudStateController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private void ensureSchema() {
        if (schemaReady) return;
        synchronized (this) {
            if (schemaReady) return;
            jdbc.execute("CREATE TABLE IF NOT EXISTS emotune_user_state (google_sub VARCHAR(255) PRIMARY KEY, email TEXT, display_name TEXT, state_json JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
            schemaReady = true;
        }
    }

    @GetMapping("/healthz")
    public Map<String, Object> health() { return Map.of("status", "ok", "service", "emotune-cloud"); }

    @GetMapping("/api/health")
    public Map<String, Object> appHealth() { return Map.of("cloud", true, "ytDlp", false, "ffmpeg", false, "ffprobe", false, "message", "La versión alojada guarda tu biblioteca en la nube; las descargas locales de escritorio no se ejecutan en Render."); }

    @PostMapping(value = "/api/download", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> downloadUnavailable() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("ok", false, "error", Map.of("code", "DESKTOP_ONLY", "message", "La descarga desde URL está disponible en la versión de escritorio, no en la versión cloud de Render.")));
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof OidcUser user)) {
            return Map.of("authenticated", false, "loginUrl", "/oauth2/authorization/google");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authenticated", true);
        result.put("name", user.getFullName() == null ? "Usuario de EMOTUNE" : user.getFullName());
        result.put("email", user.getEmail() == null ? "" : user.getEmail());
        result.put("picture", user.getPicture());
        return result;
    }

    @GetMapping(value = "/api/state", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getState(Authentication auth) {
        if (!(auth != null && auth.getPrincipal() instanceof OidcUser user)) return ResponseEntity.status(401).body(Map.of("error", "Inicia sesión con Google."));
        ensureSchema();
        var rows = jdbc.query("SELECT state_json::text, updated_at FROM emotune_user_state WHERE google_sub = ?", (rs, n) -> Map.of("json", rs.getString(1), "updatedAt", rs.getTimestamp(2).toInstant().toString()), user.getSubject());
        if (rows.isEmpty()) return ResponseEntity.noContent().build();
        try {
            JsonNode state = mapper.readTree((String) rows.get(0).get("json"));
            if (state.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) state).put("cloudUpdatedAt", (String) rows.get(0).get("updatedAt"));
            return ResponseEntity.ok(state);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "No se pudo leer el estado guardado."));
        }
    }

    @PostMapping(value = "/api/state", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> saveState(@RequestBody JsonNode state, Authentication auth, HttpServletRequest request) {
        if (!(auth != null && auth.getPrincipal() instanceof OidcUser user)) return ResponseEntity.status(401).body(Map.of("error", "Inicia sesión con Google."));
        if (!validOrigin(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Origen no permitido."));
        if (state == null || !state.isObject()) return ResponseEntity.badRequest().body(Map.of("error", "El estado debe ser un objeto JSON."));
        try {
            String json = mapper.writeValueAsString(state);
            if (json.length() > 5_000_000) return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "La biblioteca es demasiado grande. Los archivos de audio no se guardan en esta base de datos."));
            ensureSchema();
            jdbc.update("INSERT INTO emotune_user_state (google_sub, email, display_name, state_json, updated_at) VALUES (?, ?, ?, CAST(? AS JSONB), NOW()) ON CONFLICT (google_sub) DO UPDATE SET email = EXCLUDED.email, display_name = EXCLUDED.display_name, state_json = EXCLUDED.state_json, updated_at = NOW()",
                user.getSubject(), user.getEmail(), user.getFullName(), json);
            return ResponseEntity.ok(Map.of("ok", true, "savedAt", Instant.now().toString(), "message", "Biblioteca y metadatos guardados en tu cuenta."));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", "No se pudo guardar en PostgreSQL. Comprueba DATABASE_URL en Render."));
        }
    }

    @PostMapping("/api/logout")
    public ResponseEntity<?> logout(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        if (!validOrigin(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Origen no permitido."));
        new SecurityContextLogoutHandler().logout(request, response, auth);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private boolean validOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) return false;
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        String host = forwardedHost != null && !forwardedHost.isBlank() ? forwardedHost.split(",")[0].trim() : request.getHeader("Host");
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String scheme = forwardedProto != null && !forwardedProto.isBlank() ? forwardedProto.split(",")[0].trim() : request.getScheme();
        return host != null && origin.equalsIgnoreCase(scheme + "://" + host);
    }
}
