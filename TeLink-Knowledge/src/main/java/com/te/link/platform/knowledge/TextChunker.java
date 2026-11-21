package com.te.link.platform.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 按章节/段落聚合，超长段落再按字符窗口切分。片段 ID 在同一文档版本重试时稳定。 */
public class TextChunker {
    public record Chunk(String id,int ordinal,String heading,String content) { }
    public List<Chunk> split(String documentId,int version,List<DocumentParser.Block> blocks){
        List<Chunk> chunks=new ArrayList<>();String heading="";StringBuilder buffer=new StringBuilder();
        for(var block:blocks){
            String nextHeading=block.heading().substring(0,Math.min(block.heading().length(),500));
            if(!heading.equals(nextHeading) && !buffer.isEmpty()){add(chunks,documentId,version,heading,buffer.toString());buffer.setLength(0);}
            heading=nextHeading;
            if(buffer.length()+block.text().length()+1<=RagConfig.CHUNK_SIZE){if(!buffer.isEmpty())buffer.append('\n');buffer.append(block.text());continue;}
            if(!buffer.isEmpty()){add(chunks,documentId,version,heading,buffer.toString());buffer.setLength(0);}
            String text=block.text();int start=0;
            while(text.length()-start>RagConfig.CHUNK_SIZE){int end=boundary(text,start+RagConfig.CHUNK_SIZE);add(chunks,documentId,version,heading,text.substring(start,end));start=boundary(text,end-RagConfig.OVERLAP);}
            buffer.append(text.substring(start));
        }
        if(!buffer.isEmpty())add(chunks,documentId,version,heading,buffer.toString());
        if(chunks.isEmpty())throw new IllegalArgumentException("no text chunks");return chunks;
    }
    private void add(List<Chunk> list,String doc,int version,String heading,String text){
        String content=(heading.isBlank()?"":heading+"\n")+text;
        int ordinal=list.size();String id=UUID.nameUUIDFromBytes((doc+":"+version+":"+ordinal).getBytes(StandardCharsets.UTF_8)).toString();
        list.add(new Chunk(id,ordinal,heading,content));
    }
    private int boundary(String text,int position){return position>0 && position<text.length() && Character.isLowSurrogate(text.charAt(position)) && Character.isHighSurrogate(text.charAt(position-1))?position-1:position;}
}
