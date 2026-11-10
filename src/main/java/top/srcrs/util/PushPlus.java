package top.srcrs.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PushPlus 微信推送
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
        if (resp.isOk() && resp.getBody() != null) {
            LOGGER.info("PushPlus 推送结果: {}", resp.getBody());
            return resp.getBody().contains("\"code\":200");
        }
        LOGGER.warn("PushPlus 推送失败，HTTP {}", resp.getStatus());
        return false;
    }
}
