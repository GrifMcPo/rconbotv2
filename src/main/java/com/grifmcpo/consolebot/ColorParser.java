package com.grifmcpo.consolebot; 

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColorParser {

    // HEX → §x§R§R§G§G§B§B
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    // RGB → <#RRGGBB>
    private static final Pattern RGB_PATTERN = Pattern.compile("<#([A-Fa-f0-9]{6})>");

    public String parseColors(String text) {
        if (text == null) return "";

        // 1. Обрабатываем HEX: &#RRGGBB → §x§R§R§G§G§B§B
        String result = parseHex(text);

        // 2. Обрабатываем RGB: <#RRGGBB> → §x§R§R§G§G§B§B
        result = parseRGB(result);

        // 3. Обрабатываем обычные & цвета (уже есть в чате)
        result = parseAmpersand(result);

        return result;
    }

    private String parseHex(String text) {
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            String replacement = convertHexToMinecraft(hex);
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String parseRGB(String text) {
        Matcher matcher = RGB_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            String replacement = convertHexToMinecraft(hex);
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String parseAmpersand(String text) {
        return text.replace('&', '§');
    }

    private String convertHexToMinecraft(String hex) {
        // &#RRGGBB → §x§R§R§G§G§B§B
        StringBuilder sb = new StringBuilder("§x");
        for (char c : hex.toCharArray()) {
            sb.append("§").append(c);
        }
        return sb.toString();
    }

    // Метод для проверки, есть ли цвета в тексте
    public boolean hasColors(String text) {
        return HEX_PATTERN.matcher(text).find() ||
               RGB_PATTERN.matcher(text).find() ||
               text.contains("&");
    }
}
