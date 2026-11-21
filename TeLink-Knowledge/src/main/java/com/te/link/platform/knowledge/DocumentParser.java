package com.te.link.platform.knowledge;

import javax.xml.stream.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** 纯 JDK 解析 .docx 和 .txt，不执行宏、外链或文档中的指令。 */
public class DocumentParser {
    public record Block(String heading,String text) { }
    public List<Block> parse(Path file,String filename) throws Exception {
        String lower=filename.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".txt"))return text(file);
        if(lower.endsWith(".docx"))return word(file);
        throw new IllegalArgumentException("only .docx and .txt are supported; convert .doc to .docx first");
    }
    private List<Block> text(Path file) throws Exception {
        byte[] bytes=Files.readAllBytes(file);Charset charset=Charset.forName(RagConfig.TXT_CHARSET);int skip=0;
        if(bytes.length>=3 && (bytes[0]&255)==239 && (bytes[1]&255)==187 && (bytes[2]&255)==191){charset=StandardCharsets.UTF_8;skip=3;}
        else if(bytes.length>=2 && (bytes[0]&255)==255 && (bytes[1]&255)==254){charset=StandardCharsets.UTF_16LE;skip=2;}
        else if(bytes.length>=2 && (bytes[0]&255)==254 && (bytes[1]&255)==255){charset=StandardCharsets.UTF_16BE;skip=2;}
        String value=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,skip,bytes.length-skip)).toString();
        checkLength(value.length());List<Block> blocks=new ArrayList<>();String heading="";
        for(String paragraph:value.replace("\r\n","\n").replace('\r','\n').split("\n")){
            String line=paragraph.strip();if(line.isEmpty())continue;
            if(line.matches("#{1,6}\\s+.+")){heading=line.replaceFirst("^#{1,6}\\s+","");continue;}
            blocks.add(new Block(heading,line));
        }
        return nonempty(blocks);
    }
    private List<Block> word(Path file) throws Exception {
        byte[] document=null;int total=0,entries=0;
        try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(file))){
            ZipEntry entry;byte[] buffer=new byte[8192];
            while((entry=zip.getNextEntry())!=null){
                if(++entries>2000)throw new IllegalArgumentException("docx has too many entries");
                ByteArrayOutputStream output=entry.getName().equals("word/document.xml")?new ByteArrayOutputStream():null;
                int n;while((n=zip.read(buffer))!=-1){total+=n;if(total>32*1024*1024)throw new IllegalArgumentException("docx expanded size exceeds 32 MiB");if(output!=null)output.write(buffer,0,n);}
                if(output!=null)document=output.toByteArray();
            }
        }
        if(document==null)throw new IllegalArgumentException("invalid docx: missing document.xml");
        XMLInputFactory factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
        XMLStreamReader reader=factory.createXMLStreamReader(new ByteArrayInputStream(document));
        List<Block> result=new ArrayList<>();StringBuilder paragraph=new StringBuilder();String style="",heading="";boolean inText=false;int textChars=0;
        try {
            while(reader.hasNext()){
                int type=reader.next();
                if(type==XMLStreamConstants.DTD || type==XMLStreamConstants.ENTITY_REFERENCE)throw new IllegalArgumentException("docx entities are not supported");
                if(type==XMLStreamConstants.START_ELEMENT){
                    String name=reader.getLocalName();
                    if(name.equals("p")){paragraph.setLength(0);style="";}
                    if(name.equals("pStyle"))for(int i=0;i<reader.getAttributeCount();i++)if(reader.getAttributeLocalName(i).equals("val"))style=reader.getAttributeValue(i);
                    if(name.equals("t"))inText=true;
                    if(name.equals("tab"))paragraph.append('\t');
                    if(name.equals("br"))paragraph.append('\n');
                }else if(type==XMLStreamConstants.CHARACTERS && inText){paragraph.append(reader.getText());textChars+=reader.getTextLength();checkLength(textChars);}
                else if(type==XMLStreamConstants.END_ELEMENT){
                    if(reader.getLocalName().equals("t"))inText=false;
                    if(reader.getLocalName().equals("p")){
                        String value=paragraph.toString().strip();
                        if(!value.isEmpty()){
                            if(style.toLowerCase(Locale.ROOT).matches("heading[1-9].*|title|标题[1-9].*"))heading=value;
                            else result.add(new Block(heading,value));
                        }
                    }
                }
            }
        }finally{reader.close();}
        return nonempty(result);
    }
    private void checkLength(int chars){if(chars>RagConfig.MAX_TEXT)throw new IllegalArgumentException("document text exceeds configured character limit");}
    private List<Block> nonempty(List<Block> blocks){if(blocks.isEmpty())throw new IllegalArgumentException("document has no searchable text (scanned images are not supported)");return blocks;}
}
