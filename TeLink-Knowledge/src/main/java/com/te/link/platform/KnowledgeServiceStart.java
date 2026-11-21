package com.te.link.platform;
import com.te.link.platform.knowledge.*;

public class KnowledgeServiceStart {
    public static void main(String[] args)throws Exception{
        ensureSchema();
        RagRepository repository=new RagRepository();KnowledgeFiles files=new KnowledgeFiles();RagEngine engine=new RagEngine(repository,files);
        KnowledgeServer server=new KnowledgeServer(RagConfig.HOST,RagConfig.PORT,repository,files,engine);KnowledgeKafkaWorker worker=new KnowledgeKafkaWorker(repository,engine);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.close();worker.close();engine.close();},"knowledge-shutdown"));
        worker.start();server.start();System.out.println("Knowledge service listening on "+RagConfig.HOST+":"+server.port());
    }

    private static void ensureSchema() {
        if (!com.te.link.platform.log.LogMysqlConfig.enabled() && (RagConfig.MYSQL_URL == null || RagConfig.MYSQL_URL.isBlank())) {
            return;
        }
        try (var input = KnowledgeServiceStart.class.getClassLoader().getResourceAsStream("sql/knowledge.sql")) {
            if (input == null) {
                return;
            }
            String script = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(RagConfig.MYSQL_URL, RagConfig.MYSQL_USER, RagConfig.MYSQL_PASSWORD == null ? "" : RagConfig.MYSQL_PASSWORD);
                 java.sql.Statement statement = connection.createStatement()) {
                for (String line : script.split("\n")) {
                    if (line.trim().startsWith("--")) {
                        continue;
                    }
                }
                for (String sql : script.replaceAll("(?m)^--.*$", "").split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("apply knowledge.sql failed", exception);
        }
    }
}
