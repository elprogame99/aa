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
        if (raw.startsWith("postgres://") || raw.startsWith("postgresql://")) {
            try {
                URI uri = URI.create(raw.replaceFirst("^postgres://", "postgresql://"));
                String[] userPass = uri.getRawUserInfo() == null ? new String[]{"", ""} : uri.getRawUserInfo().split(":", 2);
                String user = URLDecoder.decode(userPass[0], StandardCharsets.UTF_8);
                String pass = userPass.length > 1 ? URLDecoder.decode(userPass[1], StandardCharsets.UTF_8) : "";
                String path = uri.getRawPath() == null ? "/emotune" : uri.getRawPath();
                cfg.setJdbcUrl("jdbc:postgresql://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
                cfg.setUsername(user);
                cfg.setPassword(pass);
            } catch (Exception ex) {
                throw new IllegalStateException("DATABASE_URL de PostgreSQL no tiene un formato válido.", ex);
            }
        } else if (raw.startsWith("jdbc:postgresql://")) {
            cfg.setJdbcUrl(raw);
            cfg.setUsername(env.getProperty("DB_USER", ""));
            cfg.setPassword(env.getProperty("DB_PASSWORD", ""));
        } else {
            // Permite iniciar la app aunque todavía no se haya enlazado PostgreSQL.
            // Las rutas de cuenta y sincronización mostrarán un error claro hasta configurar DATABASE_URL.
            cfg.setJdbcUrl("jdbc:postgresql://127.0.0.1:5432/emotune");
            cfg.setUsername("emotune");
            cfg.setPassword("emotune");
        }
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(5000);
        cfg.setPoolName("EmotunePool");
        return new HikariDataSource(cfg);
    }
}
