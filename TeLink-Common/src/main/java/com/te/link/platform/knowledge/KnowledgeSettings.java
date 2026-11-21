package com.te.link.platform.knowledge;

import java.nio.file.*;
import java.util.Properties;

/** 知识服务与调用端共用的配置读取规则，不初始化数据库或消息队列。 */
public final class KnowledgeSettings {
    private static final Properties LOCAL = load();
    private KnowledgeSettings() { }
    public static String value(String key, String env, String fallback) {
        String value = System.getProperty(key);
        if (value == null) value = System.getenv(env);
        if (value == null) value = LOCAL.getProperty(key);
        return value == null ? fallback : value.trim();
    }
    public static int integer(String key, String env, int fallback, int min, int max) {
        int result = Integer.parseInt(value(key, env, String.valueOf(fallback)));
        if (result < min || result > max) throw new IllegalArgumentException(key + " is out of range");
        return result;
    }
    private static Properties load() {
        Properties result = new Properties();
        Path path = Path.of(System.getProperty("telink.local.config", "config/local.properties"));
        if (Files.isRegularFile(path)) try (var input = Files.newInputStream(path)) { result.load(input); }
        catch (Exception e) { throw new IllegalStateException("load knowledge config failed", e); }
        return result;
    }
}
