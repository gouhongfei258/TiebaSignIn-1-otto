package top.srcrs.task;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.srcrs.core.SignTask;
import top.srcrs.domain.SignResult;
import top.srcrs.util.Html;
import top.srcrs.util.Http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 看雪论坛（bbs.kanxue.com）签到。
 *
 * <p>签到接口是 POST /user-signin.htm，只需要带登录 Cookie 和 csrf_token。
 * csrf_token 优先使用配置值，未配置时尝试从首页自动提取。</p>
 *
 * @author srcrs
 */
public class KanxueTask implements SignTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(KanxueTask.class);

    private static final String BASE = "https://bbs.kanxue.com";
    private static final String SIGN_URL = BASE + "/user-signin.htm";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** 首页里 csrf_token 的几种常见写法 */
    private static final Pattern[] CSRF_PATTERNS = new Pattern[]{
            Pattern.compile("<meta[^>]+name=[\"']csrf-token[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+name=[\"']csrf-token[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("name=[\"']csrf_token[\"'][^>]+value=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("csrf_token\\s*[:=]\\s*[\"']([0-9a-zA-Z]{8,})[\"']")
    };

    private final String cookie;
    private final String configuredCsrf;

    public KanxueTask(String cookie, String csrf) {
        this.cookie = cookie;
        this.configuredCsrf = csrf;
    }

    @Override
    public String getName() {
        return "看雪论坛";
    }

    @Override
    public SignResult run() {
        try {
            String csrf = configuredCsrf;
            if (csrf == null || csrf.trim().isEmpty()) {
                csrf = fetchCsrf();
            }
            if (csrf == null || csrf.trim().isEmpty()) {
                // 看雪的 csrf_token 只出现在登录后的页面里，取不到时先按空值尝试，
                // 接口若确实校验 csrf，会在返回文案里提示
                LOGGER.warn("未获取到 csrf_token，尝试不带 csrf_token 请求");
                csrf = "";
            } else {
                LOGGER.info("看雪论坛 csrf_token: {}", csrf);
            }

            Map<String, String> form = new LinkedHashMap<>();
            form.put("csrf_token", csrf);

            Http.Resp resp = Http.postForm(SIGN_URL, signHeaders(csrf), form);
            if (!resp.isOk() || resp.getBody() == null) {
                return SignResult.fail(getName(), "签到请求失败，HTTP " + resp.getStatus());
            }
            return parse(resp.getBody());
        } catch (Exception e) {
            LOGGER.error("看雪论坛签到异常 -- {}", e.toString());
            return SignResult.fail(getName(), "签到异常：" + e.getMessage());
        }
    }

    private String fetchCsrf() {
        Http.Resp resp = Http.get(BASE + "/", pageHeaders());
        if (!resp.isOk() || resp.getBody() == null) {
            LOGGER.warn("看雪论坛首页访问失败，HTTP {}", resp.getStatus());
            return null;
        }
        String body = resp.getBody();
        for (Pattern pattern : CSRF_PATTERNS) {
            Matcher matcher = pattern.matcher(body);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }

    private SignResult parse(String body) {
        String text = body.trim();
        String message = null;
        Integer code = null;
        if (text.startsWith("{")) {
            try {
                JSONObject json = JSON.parseObject(text);
                if (json != null) {
                    code = json.getInteger("code");
                    message = json.getString("message");
                    if (message == null) {
                        message = json.getString("msg");
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("看雪论坛响应不是标准 JSON -- {}", e.toString());
            }
        }
        if (message == null || message.trim().isEmpty()) {
            String plain = Html.toText(text);
            message = (plain == null || plain.isEmpty()) ? "签到完成" : plain;
        }
        String msg = message.length() > 100 ? message.substring(0, 100) + "..." : message;

        if (msg.contains("已经签到") || msg.contains("已签到") || msg.contains("已签过")) {
            return SignResult.already(getName(), msg);
        }
        if (msg.contains("请先登录") || msg.contains("未登录") || msg.contains("登录后")) {
            return SignResult.fail(getName(), "Cookie 已失效，请重新获取");
        }
        if (msg.toLowerCase().contains("csrf") || msg.contains("非法请求") || msg.contains("令牌")) {
            return SignResult.fail(getName(), "csrf_token 校验失败，请把浏览器里的 csrf_token 填到 KANXUE_CSRF");
        }
        if (code != null) {
            // 看雪约定 code=0 表示成功
            return code == 0 ? SignResult.success(getName(), msg) : SignResult.fail(getName(), msg);
        }
        return SignResult.success(getName(), msg);
    }

    private Map<String, String> pageHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Upgrade-Insecure-Requests", "1");
        headers.put("Cookie", cookie);
        return headers;
    }

    private Map<String, String> signHeaders(String csrf) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "text/plain, */*; q=0.01");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Origin", BASE);
        headers.put("Referer", BASE + "/");
        headers.put("X-Csrf-Token", csrf);
        headers.put("Cookie", cookie);
        return headers;
    }
}
