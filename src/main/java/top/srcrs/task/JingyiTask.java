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

/**
 * 精易论坛（bbs.ijingyi.com）签到。
 *
 * <p>站点是 Discuz，签到走 dsu_paulsign 插件：先从首页拿到 formhash，
 * 再 POST 到 plugin.php?id=dsu_paulsign:sign&operation=qiandao&amp;infloat=1。
 * 参考了 jingyichecker 的实现，但这里不处理极验登录，
 * 直接复用浏览器里已经登录的 Cookie，避免引入打码平台。</p>
 *
 * @author srcrs
 */
public class JingyiTask implements SignTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(JingyiTask.class);

    private static final String BASE = "https://bbs.ijingyi.com";
    private static final String SIGN_URL = BASE + "/plugin.php?id=dsu_paulsign:sign&operation=qiandao&infloat=1";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /**
     * 取 formhash 的备用入口。首页被拦时逐个试，
     * nginx 的重定向规则不一定覆盖所有路径。
     */
    private static final String[] ENTRY_PATHS = {
            "/",
            "/plugin.php?id=dsu_paulsign:sign",
            "/home.php?mod=space",
            "/member.php?mod=logging&action=login",
            "/forum.php"
    };

    private final String cookie;
    private final String emotion;
    /** 可选，配置后直接签到，完全不访问页面 */
    private final String configuredFormhash;

    public JingyiTask(String cookie, String emotion, String formhash) {
        this.cookie = cookie;
        this.emotion = (emotion == null || emotion.trim().isEmpty()) ? "kx" : emotion.trim();
        this.configuredFormhash = (formhash == null || formhash.trim().isEmpty()) ? null : formhash.trim();
    }

    @Override
    public String getName() {
        return "精易论坛";
    }

    @Override
    public SignResult run() {
        try {
            String formhash = configuredFormhash;
            if (formhash != null) {
                LOGGER.info("已配置 JINGYI_FORMHASH，跳过页面直接请求签到接口");
            } else {
                Http.Resp home = fetchEntryPage();
                if (home == null) {
                    if (isIpRejected()) {
                        return SignResult.fail(getName(), "当前出口 IP 被站点拒绝（所有入口都返回 3xx），"
                                + "代码层面无法绕过");
                    }
                    return SignResult.fail(getName(), "无法访问站点任何页面，请稍后重试");
                }
                if (!Html.isLoggedIn(home.getBody())) {
                    return SignResult.fail(getName(), "Cookie 已失效，请重新获取");
                }
                formhash = Html.extractFormhash(home.getBody());
                if (formhash == null) {
                    return SignResult.fail(getName(), "已登录但未获取到 formhash，站点模板可能已变更");
                }
            }
            LOGGER.info("精易论坛 formhash: {}", formhash);

            Map<String, String> form = new LinkedHashMap<>();
            form.put("formhash", formhash);
            form.put("submit", "1");
            form.put("targerurl", "");
            form.put("targeturl", "");
            form.put("todaysay", "");
            form.put("qdxq", emotion);

            Http.Resp resp = Http.postForm(SIGN_URL, signHeaders(), form);
            if (resp.getStatus() >= 300 && resp.getStatus() < 400) {
                return SignResult.fail(getName(), "签到接口返回 " + resp.getStatus()
                        + " 重定向，说明 POST 请求同样被站点拦截，只能更换出口网络");
            }
            if (!resp.isOk() || resp.getBody() == null) {
                return SignResult.fail(getName(), "签到请求失败：" + resp.describe());
            }
            return parse(resp.getBody());
        } catch (Exception e) {
            LOGGER.error("精易论坛签到异常 -- {}", e.toString());
            return SignResult.fail(getName(), "签到异常：" + e.getMessage());
        }
    }

    /**
     * 判断响应是否被拦住了：网络失败、非 2xx、3xx 未跳出、或者根本没有内容
     */
    private boolean isBlocked(Http.Resp resp) {
        return resp == null || !resp.isOk() || resp.getBody() == null || resp.getStatus() >= 300;
    }

    /**
     * 依次尝试各个入口，返回第一个能正常打开的页面
     */
    private Http.Resp fetchEntryPage() {
        StringBuilder tried = new StringBuilder();
        for (String path : ENTRY_PATHS) {
            Http.Resp resp = Http.get(BASE + path, pageHeaders());
            if (!isBlocked(resp)) {
                if (!"/".equals(path)) {
                    LOGGER.info("精易论坛首页不可用，改用 {} 获取到页面", path);
                }
                return resp;
            }
            if (tried.length() > 0) {
                tried.append("、");
            }
            tried.append(path).append(" → ").append(resp.getStatus() == -1 ? "网络失败" : resp.getStatus());
        }
        LOGGER.warn("精易论坛所有入口都不可用：{}", tried);
        return null;
    }

    /**
     * 判断是不是出口 IP 被站点拒绝。
     *
     * <p>判据：不带 Cookie 再请求同样被拦。这种情况下换 Cookie、换入口、加重试都没有意义，
     * 只能换出口网络。若不带 Cookie 能过，说明是 Cookie 里某个字段触发了拦截。</p>
     */
    private boolean isIpRejected() {
        try {
            Http.Resp plain = Http.get(BASE + "/", headers(false));
            if (!isBlocked(plain)) {
                LOGGER.warn("精易论坛诊断：不带 Cookie 可以正常访问（HTTP {}），"
                        + "说明 Cookie 里有字段触发了拦截，建议只保留 lDlk_163c_auth / "
                        + "lDlk_163c_saltkey / lDlk_163c_sid 三项后重试", plain.getStatus());
                return false;
            }
            LOGGER.warn("精易论坛诊断：不带 Cookie 同样返回 {}，确认与 Cookie 无关，是出口 IP 被站点拒绝",
                    plain.getStatus());
            LOGGER.warn("精易论坛：站点按来源 IP 判定，换 Cookie / 换入口 / 重试均无效；"
                    + "可配置 SIGN_PROXY 走国内代理，或改用国内 CI 运行");
            return true;
        } catch (Exception e) {
            LOGGER.debug("精易论坛诊断请求失败 -- {}", e.toString());
            return false;
        }
    }

    /**
     * 失败原因说明。3xx 说明站点在反复重定向，通常是 WAF 挑战没通过
     */
    private String describe(Http.Resp resp) {
        if (resp == null) {
            return "无响应";
        }
        int status = resp.getStatus();
        if (status >= 300 && status < 400) {
            return "站点返回 " + status + " 重定向且未能跳转成功，多半是 WAF 挑战未通过";
        }
        return resp.describe();
    }

    /**
     * 解析签到返回，可能是 JSON，也可能是 HTML 提示页
     */
    private SignResult parse(String body) {
        String text = body.trim();
        if (text.startsWith("{")) {
            try {
                JSONObject json = JSON.parseObject(text);
                if (json != null) {
                    Integer status = json.getInteger("status");
                    if (status != null && status == 1) {
                        JSONObject data = json.getJSONObject("data");
                        return SignResult.success(getName(), describe(data));
                    }
                    String msg = firstNotBlank(json.getString("msg"), json.getString("message"),
                            json.getString("data"));
                    if (msg != null) {
                        return interpret(msg);
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("精易论坛响应不是标准 JSON -- {}", e.toString());
            }
        }
        String tips = Html.textById(body, "messagetext");
        if (tips == null || tips.isEmpty()) {
            tips = Html.toText(text);
        }
        if (tips == null || tips.isEmpty()) {
            return SignResult.fail(getName(), "签到返回内容为空");
        }
        return interpret(tips);
    }

    /**
     * 根据返回文案判断是成功、已签到还是失败
     */
    private SignResult interpret(String message) {
        String msg = message.length() > 100 ? message.substring(0, 100) + "..." : message;
        if (msg.contains("已经签到") || msg.contains("已签到")) {
            return SignResult.already(getName(), "今日已签到");
        }
        if (msg.contains("来路不正确") || msg.contains("表单验证串")) {
            return SignResult.fail(getName(), configuredFormhash != null
                    ? "formhash 校验失败，JINGYI_FORMHASH 可能已过期，请重新从浏览器复制一个"
                    : "formhash 校验失败，请稍后重试");
        }
        if (msg.contains("需要先登录") || msg.contains("请先登录")) {
            if (configuredFormhash != null) {
                return SignResult.fail(getName(), "签到被拒绝，多半是 JINGYI_FORMHASH 与当前 Cookie "
                        + "不匹配（换了 Cookie 就要重新抓一次 formhash）");
            }
            return SignResult.fail(getName(), "Cookie 已失效，请重新获取");
        }
        if (msg.contains("成功") || msg.contains("精币") || msg.contains("签到")) {
            return SignResult.success(getName(), msg);
        }
        return SignResult.fail(getName(), msg);
    }

    private String describe(JSONObject data) {
        if (data == null) {
            return "签到成功";
        }
        Integer credit = data.getInteger("credit");
        Integer mdays = data.getInteger("mdays");
        StringBuilder sb = new StringBuilder("签到成功");
        if (credit != null) {
            sb.append("，获得 ").append(credit).append(" 精币");
        }
        if (mdays != null) {
            sb.append("，连续 ").append(mdays).append(" 天");
        }
        return sb.toString();
    }

    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private Map<String, String> pageHeaders() {
        return headers(true);
    }

    private Map<String, String> headers(boolean withCookie) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Upgrade-Insecure-Requests", "1");
        if (withCookie) {
            headers.put("Cookie", cookie);
        }
        // 补全浏览器指纹：部分 WAF 对 header 过少的请求会返回 302 挑战页，
        // 带上这些字段后更接近真实浏览器
        headers.put("sec-ch-ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("Sec-Fetch-Dest", "document");
        headers.put("Sec-Fetch-Mode", "navigate");
        headers.put("Sec-Fetch-Site", "none");
        headers.put("Sec-Fetch-User", "?1");
        headers.put("Cache-Control", "max-age=0");
        // 站点对部分来源 IP 会返回「301 到自己」的强制 HTTPS 跳转，
        // 带上这个头让它知道上游已经是 https，避免再跳一次
        headers.put("X-Forwarded-Proto", "https");
        return headers;
    }

    private Map<String, String> signHeaders() {
        Map<String, String> headers = pageHeaders();
        headers.put("Accept", "*/*");
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Origin", BASE);
        headers.put("Referer", BASE + "/");
        return headers;
    }
}
