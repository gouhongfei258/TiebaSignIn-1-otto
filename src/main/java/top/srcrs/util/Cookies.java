package top.srcrs.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cookie 字符串的解析与合并
 *
 * @author srcrs
 */
public final class Cookies {

    private Cookies() {
    }

    /**
     * 把 "a=1; b=2" 解析成有序 Map
     */
    public static Map<String, String> parse(String cookie) {
        Map<String, String> map = new LinkedHashMap<>();
        if (cookie == null) {
            return map;
        }
        for (String raw : cookie.split(";")) {
            String part = raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            map.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return map;
    }

    public static String get(String cookie, String name) {
        return parse(cookie).get(name);
    }

    /**
     * 新增或覆盖一个 "name=value"
     */
    public static String upsert(String cookie, String nameValue) {
        if (nameValue == null) {
            return cookie;
        }
        int eq = nameValue.indexOf('=');
        if (eq <= 0) {
            return cookie;
        }
        Map<String, String> map = parse(cookie);
        map.put(nameValue.substring(0, eq).trim(), nameValue.substring(eq + 1).trim());
        return join(map);
    }

    /**
     * 把响应里的 Set-Cookie 合并进当前 Cookie
     */
    public static String merge(String cookie, Map<String, String> responseCookies) {
        if (responseCookies == null || responseCookies.isEmpty()) {
            return cookie;
        }
        Map<String, String> map = parse(cookie);
        for (Map.Entry<String, String> entry : responseCookies.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                map.put(entry.getKey(), entry.getValue());
            }
        }
        return join(map);
    }

    private static String join(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }
}
