package com.emotune.cloud;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Configuration
public class DatabaseConfig {
    @Bean
    DataSource dataSource(Environment env) {
        String raw = env.getProperty("DATABASE_URL", "").trim();
        HikariConfig cfg = new HikariConfig();

        if (raw.isBlank()) {
            // Fail with a useful message rather than silently attempting localhost on Render.
            throw new IllegalStateException("Falta DATABASE_URL. Copia la cadena de conexión de Neon y configúrala en Render > Environment.");
        }

        try {
            if (raw.startsWith("postgres://") || raw.startsWith("postgresql://")) {
                URI uri = URI.create(raw.replaceFirst("^postgres://", "postgresql://"));
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    throw new IllegalArgumentException("La URL no contiene un host de PostgreSQL válido.");
                }
                String[] userPass = uri.getRawUserInfo() == null
                    ? new String[]{"", ""}
                    : uri.getRawUserInfo().split(":", 2);
                String user = decodeComponent(userPass[0]);
                String pass = userPass.length > 1 ? decodeComponent(userPass[1]) : "";
                String path = uri.getRawPath();
                if (path == null || path.length() <= 1) path = "/postgres";
                String query = uri.getRawQuery();
                if (query == null || query.isBlank()) query = "sslmode=require";
                else if (!query.matches("(?i).*(^|&)sslmode=.*")) query += "&sslmode=require";
                String jdbcUrl = "jdbc:postgresql://" + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + path + "?" + query;
                cfg.setJdbcUrl(jdbcUrl);
                cfg.setUsername(user);
                cfg.setPassword(pass);
            } else if (raw.startsWith("jdbc:postgresql://")) {
                cfg.setJdbcUrl(raw);
                cfg.setUsername(env.getProperty("DB_USER", ""));
                cfg.setPassword(env.getProperty("DB_PASSWORD", ""));
            } else {
                throw new IllegalArgumentException("Formato no válido. DATABASE_URL debe comenzar por postgresql://, postgres:// o jdbc:postgresql://.");
            }
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException) throw (IllegalStateException) ex;
            throw new IllegalStateException("No se pudo interpretar DATABASE_URL. Copia la cadena PostgreSQL completa de Neon sin comillas ni espacios.", ex);
        }

        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(10000);
        cfg.setValidationTimeout(5000);
        cfg.setPoolName("EmotunePool");
        return new HikariDataSource(cfg);
    }

    private static String decodeComponent(String value) {
        // URLDecoder treats '+' as a space; encode it first because URI user-info uses percent encoding.
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
