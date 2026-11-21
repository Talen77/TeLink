package com.te.link.platform.log;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class LogMysqlConfig {
    private static final Path LOCAL_CONFIG_PATH = Path.of(
            System.getProperty("telink.local.config", "config/local.properties")
    );
    private static final Properties LOCAL_PROPERTIES = loadLocalProperties();

    public static final String MYSQL_URL = first(
            readConfig("telink.log.mysql.url", "TELINK_LOG_MYSQL_URL"),
            readConfig("telink.mysql.url", "TELINK_MYSQL_URL"));
    public static final String MYSQL_USER = first(
            readConfig("telink.log.mysql.user", "TELINK_LOG_MYSQL_USER"),
            readConfig("telink.mysql.user", "TELINK_MYSQL_USER"));
    public static final String MYSQL_PASSWORD = first(
            readConfig("telink.log.mysql.password", "TELINK_LOG_MYSQL_PASSWORD"),
            readConfig("telink.mysql.password", "TELINK_MYSQL_PASSWORD"));

    public static boolean enabled() {
        return hasText(MYSQL_URL) && hasText(MYSQL_USER);
    }

    private static String readConfig(String propertyName, String envName) {
        String value = System.getProperty(propertyName);
        if (hasText(value)) {
            return value;
        }
        value = System.getenv(envName);
        if (hasText(value)) {
            return value;
        }
        return LOCAL_PROPERTIES.getProperty(propertyName);
    }

    private static String first(String preferred, String fallback) {
        return hasText(preferred) ? preferred : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 本地日志库配置与其他组件统一放在 git ignore 的 local.properties 中，方便 IDE 直接启动。
     */
    private static Properties loadLocalProperties() {
        Properties properties = new Properties();
        if (!Files.isRegularFile(LOCAL_CONFIG_PATH)) {
            return properties;
        }
        try (InputStream inputStream = Files.newInputStream(LOCAL_CONFIG_PATH)) {
            properties.load(inputStream);
            return properties;
        } catch (Exception e) {
            throw new IllegalStateException("load local log mysql config failed", e);
        }
    }
}
