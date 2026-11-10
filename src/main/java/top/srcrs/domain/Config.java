package top.srcrs.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行配置，优先从环境变量读取（GitHub Actions 的 Secrets），
 * 同时兼容通过命令行参数传入。
 *
 * <p>命令行支持两种写法：</p>
 * <pre>
 *   1. 位置参数（兼容旧版）：java -jar app.jar &lt;BDUSS&gt; &lt;SCKEY&gt;
 *   2. 键值参数：java -jar app.jar bduss=xxx kanxue=xxx enshan=xxx
 * </pre>
 *
 * @author srcrs
 */
public class Config {

    /** 百度贴吧 BDUSS */
    private String bduss;
    /** 精易论坛 Cookie */
    private String jingyiCookie;
    /** 精易论坛签到心情，默认 kx(开心) */
    private String jingyiEmotion = "kx";
    /** 看雪论坛 Cookie */
    private String kanxueCookie;
    /** 看雪论坛 csrf_token，留空则自动从首页提取 */
    private String kanxueCsrf;
    /** 恩山论坛 Cookie */
    private String enshanCookie;
    /** PushPlus 推送 token */
    private String pushToken;

    public static Config load(String[] args) {
        Config config = new Config();
        config.bduss = env("BDUSS");
        config.jingyiCookie = env("JINGYI_COOKIE", "JINGYI");
        config.jingyiEmotion = defaultIfBlank(env("JINGYI_EMOTION"), "kx");
        config.kanxueCookie = env("KANXUE_COOKIE", "KANXUE");
        config.kanxueCsrf = env("KANXUE_CSRF", "KANXUE_CSRF_TOKEN");
        config.enshanCookie = env("ENSHAN_COOKIE", "ENSHAN");
        config.pushToken = env("SCKEY", "PUSHPLUS_TOKEN");

        if (args != null) {
            List<String> positional = new ArrayList<>();
            for (String arg : args) {
                if (arg == null || arg.trim().isEmpty()) {
                    continue;
                }
                String value = arg.trim();
                int idx = value.indexOf('=');
                if (idx > 0) {
                    config.apply(value.substring(0, idx).trim(), value.substring(idx + 1).trim());
                } else {
                    positional.add(value);
                }
            }
            // 兼容旧版：第一个参数是 BDUSS，第二个参数是 SCKEY
            if (!positional.isEmpty() && isBlank(config.bduss)) {
                config.bduss = positional.get(0);
            }
            if (positional.size() > 1 && isBlank(config.pushToken)) {
                config.pushToken = positional.get(1);
            }
        }

        config.clean();
        return config;
    }

    private void apply(String key, String value) {
        switch (key.toLowerCase()) {
            case "bduss":
                this.bduss = value;
                break;
            case "jingyi":
            case "jingyicookie":
                this.jingyiCookie = value;
                break;
            case "emotion":
            case "jingyiemotion":
                this.jingyiEmotion = value;
                break;
            case "kanxue":
            case "kanxuecookie":
                this.kanxueCookie = value;
                break;
            case "csrf":
            case "kanxuecsrf":
                this.kanxueCsrf = value;
                break;
            case "enshan":
            case "enshancookie":
                this.enshanCookie = value;
                break;
            case "sckey":
            case "token":
            case "pushplus":
                this.pushToken = value;
                break;
            default:
                break;
        }
    }

    /**
     * 清理用户粘贴 Cookie 时可能带上的多余字符
     */
    private void clean() {
        this.bduss = cleanCookie(this.bduss);
        this.jingyiCookie = cleanCookie(this.jingyiCookie);
        this.kanxueCookie = cleanCookie(this.kanxueCookie);
        this.kanxueCsrf = trim(this.kanxueCsrf);
        this.enshanCookie = cleanCookie(this.enshanCookie);
        this.pushToken = trim(this.pushToken);
    }

    private static String cleanCookie(String raw) {
        String value = trim(raw);
        if (value == null) {
            return null;
        }
        if (value.regionMatches(true, 0, "cookie:", 0, 7)) {
            value = value.substring(7).trim();
        }
        // 复制 Cookie 时偶尔会把换行也带进来
        return value.replace("\r", "").replace("\n", "").trim();
    }

    private static String env(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (!isBlank(value)) {
                return value.trim();
            }
            value = System.getProperty(name);
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public boolean hasTieba() {
        return !isBlank(bduss);
    }

    public boolean hasJingyi() {
        return !isBlank(jingyiCookie);
    }

    public boolean hasKanxue() {
        return !isBlank(kanxueCookie);
    }

    public boolean hasEnshan() {
        return !isBlank(enshanCookie);
    }

    public boolean hasPush() {
        return !isBlank(pushToken);
    }

    public String getBduss() {
        return bduss;
    }

    public String getJingyiCookie() {
        return jingyiCookie;
    }

    public String getJingyiEmotion() {
        return jingyiEmotion;
    }

    public String getKanxueCookie() {
        return kanxueCookie;
    }

    public String getKanxueCsrf() {
        return kanxueCsrf;
    }

    public String getEnshanCookie() {
        return enshanCookie;
    }

    public String getPushToken() {
        return pushToken;
    }
}
