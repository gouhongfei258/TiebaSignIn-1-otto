package top.srcrs.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 恩山论坛（www.right.com.cn）的 WAF 挑战解析。
 *
 * <p>站点前面挂了一层 JS 挑战，直接访问会返回一段混淆脚本，脚本里用一维字节数组
 * 做了几轮位运算，最终通过 document.cookie 写入 https_ydclearance。
 * 这里把这段运算搬到 Java 里跑一遍，取出 Cookie 后重新请求即可正常访问。</p>
 *
 * <p>算法与脚本参数均从页面中动态提取，脚本结构变化时只会解析失败而不会抛异常。</p>
 *
 * @author srcrs
 */
public final class EnshanWaf {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnshanWaf.class);

    /** 字节数组 oo = [ ... ] */
    private static final Pattern OO_ARRAY = Pattern.compile("oo\\s*=\\s*\\[([^\\]]+)\\]");
    /** 数组中的数字，支持 0x 十六进制 */
    private static final Pattern NUMBER = Pattern.compile("0x[0-9a-fA-F]+|\\d+");
    /** 解密密钥 wi */
    private static final Pattern WI_TIMEOUT = Pattern.compile("setTimeout\\(\"\\w+\\((\\d+)\\)\"");
    private static final Pattern WI_CALL = Pattern.compile("\\b\\w+\\((\\d+)\\)");
    /** 第一轮：取反、循环右移、减常数 */
    private static final Pattern LOOP1 = Pattern.compile(
            "qo\\s*=\\s*(\\d+);\\s*do\\s*\\{.*?oo\\[qo\\]\\s*=\\s*\\(\\s*-oo\\[qo\\]\\s*\\)\\s*&\\s*0xff\\s*;.*?"
                    + "oo\\[qo\\]\\s*=\\s*\\(\\(\\s*\\(oo\\[qo\\]\\s*>>\\s*(\\d+)\\)\\s*\\|\\s*\\(\\s*\\(oo\\[qo\\]\\s*<<\\s*(\\d+)\\)"
                    + "\\s*&\\s*0xff\\)\\)\\s*-\\s*(\\d+)\\)\\s*&\\s*0xff\\s*;.*?"
                    + "\\}\\s*while\\s*\\(\\s*--\\s*qo\\s*>=\\s*2\\s*\\)",
            Pattern.DOTALL);
    /** 第二轮：相邻字节做差 */
    private static final Pattern LOOP2 = Pattern.compile(
            "qo\\s*=\\s*(\\d+);\\s*do\\s*\\{[^}]*?oo\\[qo\\]\\s*=\\s*\\(oo\\[qo\\]\\s*-\\s*oo\\[qo\\s*-\\s*1\\]\\)"
                    + "\\s*&\\s*0xff;[^}]*?\\}\\s*while\\s*\\(\\s*--\\s*qo\\s*>=\\s*3\\s*\\)",
            Pattern.DOTALL);
    /** 第三轮：加常数后循环左移 */
    private static final Pattern LOOP3_BLOCK = Pattern.compile(
            "qo\\s*=\\s*1;\\s*for\\s*\\(.*?\\)\\s*\\{(.*?)\\}\\s*po\\s*=", Pattern.DOTALL);
    private static final Pattern LOOP3_UPPER = Pattern.compile("qo\\s*>\\s*(\\d+)\\)\\s*break");
    private static final Pattern LOOP3_ASSIGN = Pattern.compile("oo\\[qo\\]\\s*=\\s*(.+?);", Pattern.DOTALL);
    private static final Pattern ADD_NUMBER = Pattern.compile("\\+\\s*(\\d+)");
    private static final Pattern SHIFT_NUMBER = Pattern.compile("<<\\s*(\\d+)|>>\\s*(\\d+)");
    /** 输出时跳过的下标间隔 */
    private static final Pattern MOD_SKIP = Pattern.compile("qo\\s*%\\s*(\\d+)");
    /** 解密结果里的 document.cookie */
    private static final Pattern DOCUMENT_COOKIE = Pattern.compile("document\\.cookie=['\"]([^'\"]+)['\"]");

    private EnshanWaf() {
    }

    /**
     * 解析 WAF 页面，返回需要写入的 Cookie（name=value），失败返回 null
     */
    public static String resolveCookie(String html) {
        if (html == null) {
            return null;
        }
        try {
            int[] oo = extractOo(html);
            Integer wi = extractWi(html);
            if (oo == null || wi == null) {
                LOGGER.warn("WAF 响应解析失败，无法提取数组或密钥");
                return null;
            }

            Matcher loop1 = LOOP1.matcher(html);
            Matcher loop2 = LOOP2.matcher(html);
            Matcher loop3Block = LOOP3_BLOCK.matcher(html);
            if (!loop1.find() || !loop2.find() || !loop3Block.find()) {
                LOGGER.warn("WAF 解密参数提取失败");
                return null;
            }

            String block = loop3Block.group(1);
            Integer upper = firstInt(LOOP3_UPPER, block);
            Matcher assign = LOOP3_ASSIGN.matcher(block);
            if (upper == null || !assign.find()) {
                LOGGER.warn("WAF 第三轮参数提取失败");
                return null;
            }
            String expr = assign.group(1);
            List<Integer> adds = allInts(ADD_NUMBER, expr);
            List<Integer> shifts = allInts(SHIFT_NUMBER, expr);
            if (adds.size() < 2 || shifts.size() < 2) {
                LOGGER.warn("WAF 第三轮表达式解析失败");
                return null;
            }

            Integer modSkip = firstInt(MOD_SKIP, html);

            Params params = new Params();
            params.loop1Start = Integer.parseInt(loop1.group(1));
            params.shiftRight = Integer.parseInt(loop1.group(2));
            params.shiftLeft = Integer.parseInt(loop1.group(3));
            params.subtract = Integer.parseInt(loop1.group(4));
            params.loop2Start = Integer.parseInt(loop2.group(1));
            params.loop3Upper = upper;
            params.add1 = adds.get(0);
            params.add2 = adds.get(1);
            params.rotateLeft = shifts.get(0);
            params.modSkip = modSkip == null ? 7 : modSkip;

            String decoded = decode(oo, wi, params);
            Matcher cookieMatcher = DOCUMENT_COOKIE.matcher(decoded);
            if (!cookieMatcher.find()) {
                LOGGER.warn("WAF 解密成功但未找到 Cookie 写入语句");
                return null;
            }
            String cookie = cookieMatcher.group(1).trim();
            if (cookie.isEmpty()) {
                return null;
            }
            int semicolon = cookie.indexOf(';');
            return (semicolon > 0 ? cookie.substring(0, semicolon) : cookie).trim();
        } catch (Exception e) {
            LOGGER.warn("WAF 解析异常 -- {}", e.toString());
            return null;
        }
    }

    private static String decode(int[] ooHex, int wi, Params p) {
        int[] oo = new int[ooHex.length];
        for (int i = 0; i < ooHex.length; i++) {
            oo[i] = ooHex[i] & 0xFF;
        }
        if (oo.length < 6) {
            return "";
        }
        int last = oo.length - 1;

        // 第一轮
        int qo = Math.min(p.loop1Start, last - 1);
        while (true) {
            oo[qo] = (-oo[qo]) & 0xFF;
            if (p.shiftRight + p.shiftLeft == 8) {
                oo[qo] = (rotr8(oo[qo], p.shiftRight) - p.subtract) & 0xFF;
            } else {
                oo[qo] = (((oo[qo] >> p.shiftRight) | ((oo[qo] << p.shiftLeft) & 0xFF)) - p.subtract) & 0xFF;
            }
            qo--;
            if (qo < 2) {
                break;
            }
        }

        // 第二轮
        qo = Math.min(p.loop2Start, last - 2);
        while (true) {
            oo[qo] = (oo[qo] - oo[qo - 1]) & 0xFF;
            qo--;
            if (qo < 3) {
                break;
            }
        }

        // 第三轮
        for (qo = 1; qo <= Math.min(p.loop3Upper, last - 1); qo++) {
            int x = (oo[qo] + p.add1) & 0xFF;
            x = (x + p.add2) & 0xFF;
            oo[qo] = rotl8(x, p.rotateLeft);
        }

        // 输出
        StringBuilder sb = new StringBuilder();
        for (qo = 1; qo < last; qo++) {
            if (qo % p.modSkip != 0) {
                sb.append((char) ((oo[qo] ^ (wi & 0xFF)) & 0xFF));
            }
        }
        return sb.toString();
    }

    private static int rotl8(int x, int r) {
        x &= 0xFF;
        r &= 7;
        return ((x << r) & 0xFF) | (x >> (8 - r));
    }

    private static int rotr8(int x, int r) {
        x &= 0xFF;
        r &= 7;
        return (x >> r) | ((x << (8 - r)) & 0xFF);
    }

    private static int[] extractOo(String html) {
        Matcher matcher = OO_ARRAY.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        Matcher number = NUMBER.matcher(matcher.group(1));
        List<Integer> values = new ArrayList<>();
        while (number.find()) {
            String token = number.group();
            values.add(token.toLowerCase().startsWith("0x")
                    ? Integer.parseInt(token.substring(2), 16)
                    : Integer.parseInt(token));
        }
        if (values.isEmpty()) {
            return null;
        }
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private static Integer extractWi(String html) {
        Integer value = firstInt(WI_TIMEOUT, html);
        return value != null ? value : firstInt(WI_CALL, html);
    }

    private static Integer firstInt(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private static List<Integer> allInts(Pattern pattern, String text) {
        List<Integer> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                String group = matcher.group(i);
                if (group != null) {
                    values.add(Integer.parseInt(group));
                }
            }
        }
        return values;
    }

    /**
     * 从脚本中提取出来的运算参数
     */
    private static class Params {
        int loop1Start;
        int loop2Start;
        int loop3Upper;
        int shiftRight;
        int shiftLeft;
        int subtract;
        int add1;
        int add2;
        int rotateLeft;
        int modSkip;
    }
}
