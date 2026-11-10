package top.srcrs.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PushPlus 微信推送
 *
 * <p>接口：GET/POST https://www.pushplus.plus/send
 * 参数：token、title、content、template
 * 返回：{"code":200,"msg":"请求成功","data":"流水号"}</p>
 *
 * <p>注意 code=200 只代表请求被接收，实际投递结果需要用流水号查询，
 * 或者配置 callbackUrl 接收回调。</p>
 *
 * @author srcrs
 */
public final class PushPlus {

    private static final Logger LOGGER = LoggerFactory.getLogger(PushPlus.class);

    private static final String SEND_URL = "https://www.pushplus.plus/send";

    private PushPlus() {
    }

    /**
     * 发送文本消息
     *
     * @param token   PushPlus 的 token（原 SCKEY）
     * @param title   标题
     * @param content 正文
     */
    public static boolean send(String token, String title, String content) {
        if (token == null || token.trim().isEmpty()) {
            LOGGER.warn("未配置 PushPlus token，跳过推送");
            return false;
        }
        String url = SEND_URL
                + "?token=" + Http.encode(token)
                + "&title=" + Http.encode(title)
                + "&content=" + Http.encode(content)
                + "&template=txt";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        Http.Resp resp = Http.get(url, headers);
        if (resp.getBody() == null) {
            LOGGER.warn("PushPlus 推送失败，HTTP {}", resp.getStatus());
            return false;
        }

        try {
            JSONObject json = JSON.parseObject(resp.getBody());
            if (json == null) {
                LOGGER.warn("PushPlus 返回内容无法解析: {}", resp.getBody());
                return false;
            }
            Integer code = json.getInteger("code");
            String msg = json.getString("msg");
            if (code != null && code == 200) {
                LOGGER.info("PushPlus 推送成功，流水号 {}", json.getString("data"));
                return true;
            }
            LOGGER.warn("PushPlus 推送失败：code={} msg={} {}", code, msg, hint(code));
            return false;
        } catch (Exception e) {
            LOGGER.warn("PushPlus 返回内容无法解析: {} -- {}", resp.getBody(), e.toString());
            return false;
        }
    }

    /**
     * 把常见返回码翻译成可操作的提示
     */
    private static String hint(Integer code) {
        if (code == null) {
            return "";
        }
        switch (code) {
            case 905:
                return "（账号未实名认证，需先在 pushplus 个人中心完成认证才能发消息）";
            case 903:
                return "（token 不正确，检查 SCKEY 是否复制完整）";
            case 900:
                return "（请求过于频繁，账号被临时限制，稍后再试）";
            case 403:
                return "（请求 IP 未授权，检查开放接口的 IP 白名单）";
            case 888:
                return "（积分不足，需要充值）";
            case 302:
            case 401:
                return "（未登录或授权失效，重新登录 pushplus 获取 token）";
            default:
                return "";
        }
    }
}
