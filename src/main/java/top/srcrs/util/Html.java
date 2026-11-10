package top.srcrs.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简单的 HTML 解析工具，只依赖正则，避免引入额外依赖
 *
 * @author srcrs
 */
public final class Html {

    /** Discuz 退出链接中的 formhash，只在登录状态下存在 */
    private static final Pattern LOGOUT_FORMHASH = Pattern.compile(
            "member\\.php\\?mod=logging(?:&amp;|&)action=logout(?:&amp;|&)formhash=([0-9a-zA-Z]+)");

    /** 页面中的隐藏域 formhash */
    private static final Pattern INPUT_FORMHASH = Pattern.compile(
            "name=[\"']formhash[\"']\\s+value=[\"']([0-9a-zA-Z]+)[\"']");

    /** 兜底的 formhash 匹配 */
    private static final Pattern GENERIC_FORMHASH = Pattern.compile(
            "formhash=([0-9a-zA-Z]{8})");

    private static final Pattern SCRIPT = Pattern.compile("(?is)<script.*?</script>");
    private static final Pattern STYLE = Pattern.compile("(?is)<style.*?</style>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern BLANK = Pattern.compile("[ \\t\\x0B\\f\\r]+");

    private Html() {
    }

    /**
     * 从页面中提取 formhash，取不到返回 null
     */
    public static String extractFormhash(String html) {
        if (html == null) {
            return null;
        }
        String value = firstGroup(LOGOUT_FORMHASH, html);
        if (value == null) {
            value = firstGroup(INPUT_FORMHASH, html);
        }
        if (value == null) {
            value = firstGroup(GENERIC_FORMHASH, html);
        }
        return value;
    }

    /**
     * 判断 Discuz 页面是否处于登录状态。
     * 游客页面同样会带一个 formhash（搜索表单里也有），所以不能只看 formhash，
     * 必须找到只有登录后才渲染的退出链接。
     */
    public static boolean isLoggedIn(String html) {
        if (html == null) {
            return false;
        }
        return html.contains("action=logout") || html.contains("action%3Dlogout");
    }

    /**
     * 提取 id="xxx" 元素内部的文本，用于读取 Discuz 的提示信息
     */
    public static String textById(String html, String id) {
        if (html == null) {
            return null;
        }
        String key = "id=\"" + id + "\"";
        int start = html.indexOf(key);
        if (start < 0) {
            key = "id='" + id + "'";
            start = html.indexOf(key);
        }
        if (start < 0) {
            return null;
        }
        int end = html.indexOf("</div>", start);
        String block = end > start ? html.substring(start, end) : html.substring(start);
        return toText(block);
    }

    /**
     * 去掉标签与脚本，返回纯文本
     */
    public static String toText(String html) {
        if (html == null) {
            return null;
        }
        String text = SCRIPT.matcher(html).replaceAll(" ");
        text = STYLE.matcher(text).replaceAll(" ");
        text = TAG.matcher(text).replaceAll(" ");
        text = unescape(text);
        text = BLANK.matcher(text).replaceAll(" ");
        text = text.replaceAll("\\s*\\n\\s*", "\n");
        return text.trim();
    }

    /**
     * 处理常见的 HTML 实体
     */
    public static String unescape(String text) {
        if (text == null) {
            return null;
        }
        return text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static String firstGroup(Pattern pattern, String html) {
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            String value = matcher.group(1);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
