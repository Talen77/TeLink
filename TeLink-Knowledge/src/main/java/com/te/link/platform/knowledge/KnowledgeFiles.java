package com.te.link.platform.knowledge;

import java.nio.file.*;
import java.util.*;

public class KnowledgeFiles {
    private final Path root;
    public KnowledgeFiles(){this(RagConfig.FILE_ROOT);}
    public KnowledgeFiles(Path root){this.root=root.toAbsolutePath().normalize();}
    public String save(String filename,byte[] bytes)throws Exception{
        validateName(filename);if(bytes.length==0 || bytes.length>KnowledgeHttpClient.MAX_UPLOAD_BYTES)throw new IllegalArgumentException("file must be between 1 byte and 10 MiB");
        Files.createDirectories(root);
        String key=UUID.randomUUID()+ (filename.toLowerCase(Locale.ROOT).endsWith(".docx")?".docx":".txt");
        Files.write(resolve(key),bytes,StandardOpenOption.CREATE_NEW);return key;
    }
    public Path resolve(String key){if(key==null || !key.matches("[0-9a-f-]{36}\\.(txt|docx)"))throw new IllegalArgumentException("invalid stored file key");return root.resolve(key);}
    public void delete(String key)throws Exception{Files.deleteIfExists(resolve(key));}
    public static void validateName(String name){if(name==null || name.isBlank() || name.length()>255 || name.contains("/") || name.contains("\\") || name.indexOf('\0')>=0 || !(name.toLowerCase(Locale.ROOT).endsWith(".txt") || name.toLowerCase(Locale.ROOT).endsWith(".docx")))throw new IllegalArgumentException("upload a .docx or .txt file with a valid filename");}
}
