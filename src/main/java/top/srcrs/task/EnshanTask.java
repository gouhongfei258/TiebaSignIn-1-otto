package top.srcrs.task;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.srcrs.core.SignTask;
import top.srcrs.domain.SignResult;
import top.srcrs.util.Cookies;
import top.srcrs.util.EnshanWaf;
import top.srcrs.util.Html;
import top.srcrs.util.Http;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 恩山论坛（www.right.com.cn）签到。
 *
 * <p>站点同样是 Discuz，签到走 erling_qd 插件。区别在于前面挂了一层 WAF：
 * 首次访问会返回一段 JS 挑战，需要先算出 https_ydclearance 再重新请求，
 * 否则拿不到 formhash。WAF 的运算逻辑见 {@link EnshanWaf}。</p>
 *
 * @author srcrs
 */
public class EnshanTask implements SignTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnshanTask.class);

    private static final String BASE = "https://www.right.com.cn/forum";
    private static final String SIGN_PAGE = BASE + "/erling_qd-sign_in.html";
    private static final String SIGN_URL = BASE + "/plugin.php?id=erling_qd:action&action=sign";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private String cookie;
    private String formhash;

    public EnshanTask(String cookie) {
        this.cookie = cookie;
    }

    @Override
    public String getName() {
        return "恩山论坛";
    }

    @Override
    public SignResult run() {
        try {
            String html = openSignPage();
            if (html == null) {
                return SignResult.fail(getName(), "打开签到页面失败，可能是网络或 Cookie 问题");
            }
            if (Html.extractFormhash(html) == null && isSliderChallenge(html)) {
                return SignResult.fail(getName(), "被站点 WAF 拦截（需要滑块验证），请稍后重试或更换网络环境");
            }
            formhash = Html.extractFormhash(html);
            if (formhash == null) {
                return SignResult.fail(getName(), "未获取到 formhash，Cookie 可能已失效");
            }
            if (!Html.isLoggedIn(html)) {
                return SignResult.fail(getName(), "Cookie 已失效，请重新获取");
            }
            LOGGER.info("恩山论坛 formhash: {}", formhash);

            Map<String, String> form = new LinkedHashMap<>();
            form.put("formhash", formhash);

            Http.Resp resp = Http.postForm(SIGN_URL, signHeaders(), form);
            if (!resp.isOk() || resp.getBody() == null) {
                return SignResult.fail(getName(), "签到请求失败，HTTP " + resp.getStatus());
            }
            return parse(resp.getBody());
        } catch (Exception e) {
            LOGGER.error("恩山论坛签到异常 -- {}", e.toString());
            return SignResult.fail(getName(), "签到异常：" + e.getMessage());
        }
    }

    /**
     * 打开签到页面，遇到 WAF 挑战时先解出 Cookie 再重试一次
     */
    private String openSignPage() {
        Http.Resp resp = Http.get(SIGN_PAGE, pageHeaders());
        if (resp.getBody() == null) {
            return null;
        }
        cookie = Cookies.merge(cookie, resp.getCookies());
        String html = resp.getBody();
        if (Html.extractFormhash(html) != null && Html.isLoggedIn(html)) {
            return html;
        }

        LOGGER.info("恩山论坛未拿到 formhash，尝试解析 WAF 挑战");
        String clearance = EnshanWaf.resolveCookie(html);
        if (clearance == null) {
            return html;
        }
        cookie = Cookies.upsert(cookie, clearance);
        LOGGER.info("已获取并更新 {} Cookie", clearance.split("=")[0]);

        Http.Resp retry = Http.get(SIGN_PAGE, pageHeaders());
        cookie = Cookies.merge(cookie, retry.getCookies());
        return retry.getBody() == null ? html : retry.getBody();
    }

    /**
     * 阿里云滑块验证页，无法在无头环境里通过
     */
    private boolean isSliderChallenge(String html) {
        return html != null && (html.contains("aliyunCaptcha") || html.contains("AC_Opt")
                || html.contains("renderData"));
    }

    private SignResult parse(String body) {
        String text = body.trim();
        if (text.startsWith("{")) {
            try {
                JSONObject json = JSON.parseObject(text);
                if (json != null) {
                    String msg = json.getString("message");
                    if (msg == null) {
                        msg = json.getString("msg");
                    }
                    Integer code = json.getInteger("code");
                    Integer status = json.getInteger("status");
                    if (msg == null || msg.trim().isEmpty()) {
                        msg = code != null ? "返回码 " + code : "签到完成";
                    }
                    return interpret(msg, code, status);
                }
            } catch (Exception e) {
                LOGGER.debug("恩山论坛响应不是标准 JSON -- {}", e.toString());
            }
        }
        String tips = Html.textById(body, "messagetext");
        if (tips == null || tips.isEmpty()) {
            tips = Html.toText(text);
        }
        if (tips == null || tips.isEmpty()) {
            return SignResult.fail(getName(), "签到返回内容为空");
        }
        return interpret(tips, null, null);
    }

    private SignResult interpret(String message, Integer code, Integer status) {
        String msg = message.length() > 100 ? message.substring(0, 100) + "..." : message;
        if (msg.contains("已经签到") || msg.contains("已签到") || msg.contains("已签过")) {
            return SignResult.already(getName(), "今日已签到");
        }
        if (msg.contains("请先登录") || msg.contains("未登录")) {
            return SignResult.fail(getName(), "Cookie 已失效，请重新获取");
        }
        if (code != null) {
            return code == 0 ? SignResult.success(getName(), msg) : SignResult.fail(getName(), msg);
        }
        if (status != null) {
            return status == 1 ? SignResult.success(getName(), msg) : SignResult.fail(getName(), msg);
        }
        if (msg.contains("成功") || msg.contains("积分") || msg.contains("经验")) {
            return SignResult.success(getName(), msg);
        }
        return SignResult.fail(getName(), msg);
    }

    private Map<String, String> pageHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,"
                + "image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Cache-Control", "no-cache");
        headers.put("Pragma", "no-cache");
        headers.put("Upgrade-Insecure-Requests", "1");
        headers.put("Referer", SIGN_PAGE);
        headers.put("Cookie", cookie);
        return headers;
    }

    private Map<String, String> signHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "application/json, text/javascript, */*; q=0.01");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Origin", "https://www.right.com.cn");
        headers.put("Referer", SIGN_PAGE);
        headers.put("Cookie", cookie);
        return headers;
    }
}
