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

    private final String cookie;
    private final String emotion;

    public JingyiTask(String cookie, String emotion) {
        this.cookie = cookie;
        this.emotion = (emotion == null || emotion.trim().isEmpty()) ? "kx" : emotion.trim();
    }

    @Override
    public String getName() {
        return "精易论坛";
    }

    @Override
    public SignResult run() {
        try {
            Http.Resp home = Http.get(BASE + "/", pageHeaders());
            if (!home.isOk() || home.getBody() == null) {
                return SignResult.fail(getName(), "访问首页失败，HTTP " + home.getStatus());
            }
            if (!Html.isLoggedIn(home.getBody())) {
                return SignResult.fail(getName(), "Cookie 已失效，请重新获取");
            }
            String formhash = Html.extractFormhash(home.getBody());
            if (formhash == null) {
                return SignResult.fail(getName(), "已登录但未获取到 formhash，站点模板可能已变更");
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
            if (!resp.isOk() || resp.getBody() == null) {
                return SignResult.fail(getName(), "签到请求失败，HTTP " + resp.getStatus());
            }
            return parse(resp.getBody());
        } catch (Exception e) {
            LOGGER.error("精易论坛签到异常 -- {}", e.toString());
            return SignResult.fail(getName(), "签到异常：" + e.getMessage());
        }
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
        if (msg.contains("需要先登录") || msg.contains("请先登录")) {
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
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Upgrade-Insecure-Requests", "1");
        headers.put("Cookie", cookie);
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
