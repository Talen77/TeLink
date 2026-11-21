package com.te.link.platform.knowledge;

import com.te.link.platform.log.LogMysqlConfig;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

public final class RagConfig {
    public static final String HOST = read("host", "127.0.0.1");
    public static final int PORT = number("port", 9093, 1, 65535);
    public static final String API_KEY = read("apiKey", "");
    public static final Path FILE_ROOT = Path.of(read("storagePath", ".data/knowledge")).toAbsolutePath().normalize();
    public static final String MYSQL_URL = read("mysql.url", LogMysqlConfig.MYSQL_URL);
    public static final String MYSQL_USER = read("mysql.user", LogMysqlConfig.MYSQL_USER);
    public static final String MYSQL_PASSWORD = read("mysql.password", LogMysqlConfig.MYSQL_PASSWORD);
    // 与推送节点 WebSocketGatewayConfig 和业务服务 BusinessKafkaConfig 使用同一云端默认地址。
    public static final String KAFKA = read("kafka.bootstrapServers", KnowledgeSettings.value(
            "telink.kafka.bootstrapServers", "TELINK_KAFKA_BOOTSTRAP_SERVERS",
            KnowledgeSettings.value("telink.kafka.bootstrap", "TELINK_KAFKA_BOOTSTRAP", "127.0.0.1:9092")));
    public static final String TOPIC = read("kafka.topic", "knowledge_ingest");
    public static final String DLQ_TOPIC = read("kafka.deadLetterTopic", "knowledge_ingest_dlq");
    public static final String GROUP = read("kafka.group", "knowledge-indexer");
    public static final String EMBEDDING_URL = read("embedding.url", "");
    public static final String EMBEDDING_KEY = text(read("embedding.apiKey", ""), System.getenv("TELINK_EMBED_KEY"));
    public static final String EMBEDDING_MODEL = read("embedding.model", "");
    public static final int DIMENSIONS = number("embedding.dimensions", 1024, 1, 65536);
    public static final String QDRANT_URL = read("qdrant.url", "http://127.0.0.1:6333").replaceAll("/+$", "");
    public static final String QDRANT_KEY = read("qdrant.apiKey", "");
    public static final String COLLECTION = read("qdrant.collection", "telink_knowledge");
    public static final int CHUNK_SIZE = number("chunk.size", 1200, 200, 4000);
    public static final int OVERLAP = number("chunk.overlap", 150, 0, CHUNK_SIZE / 2);
    public static final int MAX_TEXT = number("maxTextChars", 200000, 1000, 1000000);
    public static final int MAX_ATTEMPTS = number("maxAttempts", 3, 1, 10);
    public static final String TXT_CHARSET = read("txt.charset", "UTF-8");
    public static final String PROFILE = hash(EMBEDDING_URL + "|" + EMBEDDING_MODEL + "|" + DIMENSIONS + "|" + read("embedding.revision", "1") + "|lexical-v1");
    private RagConfig() { }
    private static String text(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : (fallback == null ? "" : fallback);
    }
    public static String read(String suffix, String fallback) {
        String env = "TELINK_KNOWLEDGE_" + suffix.replaceAll("([a-z])([A-Z])", "$1_$2").replace('.', '_').toUpperCase();
        return KnowledgeSettings.value("telink.knowledge." + suffix, env, fallback);
    }
    private static int number(String suffix, int fallback, int min, int max) {
        int value = Integer.parseInt(read(suffix, String.valueOf(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException("invalid knowledge config " + suffix);
        return value;
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static Properties kafkaProperties() {
        Properties p = new Properties(); p.put("bootstrap.servers", KAFKA);
        p.put("security.protocol", read("kafka.securityProtocol", "PLAINTEXT"));
        String mechanism = read("kafka.saslMechanism", "");
        String jaas = read("kafka.saslJaasConfig", "");
        if (!mechanism.isEmpty()) p.put("sasl.mechanism", mechanism);
        if (!jaas.isEmpty()) p.put("sasl.jaas.config", jaas);
        return p;
    }
}
