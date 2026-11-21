package com.te.link.platform.knowledge;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** 英文术语/异常类名 + 中文双字词，稳定 hash 特征；对数 TF 配合 Qdrant IDF。不是完整 BM25。 */
public final class LexicalVector {
    private static final Pattern WORD=Pattern.compile("[a-zA-Z0-9_.$:-]+|[\\p{IsHan}]+");
    public static boolean overlaps(String query, String content) {
        Set<String> left = terms(query);
        if (left.isEmpty()) return false;
        left.retainAll(terms(content));
        return !left.isEmpty();
    }
    public static Set<String> terms(String text) {
        Set<String> terms = new HashSet<>();
        if (text == null || text.isBlank()) return terms;
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) collect(terms, matcher.group());
        return terms;
    }
    private static void collect(Set<String> terms, String word) {
        if (Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN) {
            int[] chars = word.codePoints().toArray();
            if (chars.length == 1) terms.add(word);
            else for (int i = 0; i < chars.length - 1; i++) terms.add(new String(chars, i, 2));
        } else {
            terms.add(word);
            for (String part : word.split("[.$:_-]+")) if (!part.equals(word) && part.length() > 1) terms.add(part);
        }
    }
    public static JsonObject of(String text){
        Map<Long,Integer> frequencies=new TreeMap<>();
        for (String term : terms(text)) add(frequencies, term);
        JsonArray indices=new JsonArray(),values=new JsonArray();frequencies.forEach((id,count)->{indices.add(id);values.add(1+Math.log(count));});JsonObject result=new JsonObject();result.add("indices",indices);result.add("values",values);return result;
    }
    private static void add(Map<Long,Integer> frequencies,String term){int hash=0x811c9dc5;for(byte b:term.getBytes(StandardCharsets.UTF_8)){hash^=b&255;hash*=0x01000193;}frequencies.merge(Integer.toUnsignedLong(hash),1,Integer::sum);}
}
