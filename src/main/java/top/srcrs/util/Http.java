package top.srcrs.util;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用 HTTP 工具类，供各论坛签到使用
 *
 * @author srcrs
 */
public final class Http {

    private static final Logger LOGGER = LoggerFactory.getLogger(Http.class);

    /** 连接与读取超时（毫秒） */
    private static final int TIMEOUT = 30_000;

    private Http() {
    }

    /**
     * 简易响应对象
     */
    public static class Resp {
        private final int status;
        private final String body;
        private final Map<String, String> cookies;

        Resp(int status, String body, Map<String, String> cookies) {
            this.status = status;
            this.body = body;
            this.cookies = cookies;
        }

        public int getStatus() {
            return status;
        }

        public String getBody() {
            return body;
        }

        public Map<String, String> getCookies() {
            return cookies;
        }

        public boolean isOk() {
            return status >= 200 && status < 400;
        }

        public String getCookie(String name) {
            if (cookies == null || name == null) {
                return null;
            }
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    public static Resp get(String url, Map<String, String> headers) {
        HttpGet request = new HttpGet(url);
        applyHeaders(request, headers);
        return execute(request);
    }

    public static Resp post(String url, Map<String, String> headers, String body, String contentType) {
        HttpPost request = new HttpPost(url);
        applyHeaders(request, headers);
        if (body != null) {
            StringEntity entity = new StringEntity(body, ContentType.create(contentType, StandardCharsets.UTF_8));
            request.setEntity(entity);
        }
        return execute(request);
    }

    /**
     * 以 application/x-www-form-urlencoded 提交表单
     */
    public static Resp postForm(String url, Map<String, String> headers, Map<String, String> form) {
        return post(url, headers, encodeForm(form), "application/x-www-form-urlencoded");
    }

    /**
     * 表单编码，null 值按空字符串处理
     */
    public static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        if (form == null) {
            return "";
        }
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return sb.toString();
    }

    public static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static void applyHeaders(HttpRequestBase request, Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                request.setHeader(entry.getKey(), entry.getValue());
            }
        }
    }

    private static Resp execute(HttpRequestBase request) {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(TIMEOUT)
                .setSocketTimeout(TIMEOUT)
                .setConnectionRequestTimeout(TIMEOUT)
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(config)
                // 关闭内置 Cookie 管理，统一由各任务自己维护 Cookie 头
                .disableCookieManagement()
                .setRedirectStrategy(new LaxRedirectStrategy())
                .build();
        try {
            CloseableHttpResponse response = client.execute(request);
            try {
                int status = response.getStatusLine().getStatusCode();
                String body = readBody(response.getEntity());
                Map<String, String> cookies = parseCookies(response.getHeaders("Set-Cookie"));
                LOGGER.debug("{} {} -> {}", request.getMethod(), request.getURI(), status);
                return new Resp(status, body, cookies);
            } finally {
                response.close();
            }
        } catch (Exception e) {
            LOGGER.warn("请求异常 {} -- {}", request.getURI(), e.toString());
            return new Resp(-1, null, new LinkedHashMap<String, String>());
        } finally {
            try {
                client.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }

    private static String readBody(HttpEntity entity) {
        if (entity == null) {
            return null;
        }
        try {
            Charset charset = StandardCharsets.UTF_8;
            ContentType contentType = ContentType.get(entity);
            if (contentType != null && contentType.getCharset() != null) {
                charset = contentType.getCharset();
            }
            return EntityUtils.toString(entity, charset);
        } catch (Exception e) {
            LOGGER.warn("读取响应内容失败 -- {}", e.toString());
            return null;
        }
    }

    private static Map<String, String> parseCookies(Header[] setCookieHeaders) {
        Map<String, String> cookies = new LinkedHashMap<>();
        if (setCookieHeaders == null) {
            return cookies;
        }
        for (Header header : setCookieHeaders) {
            String value = header.getValue();
            if (value == null) {
                continue;
            }
            int semicolon = value.indexOf(';');
            String pair = semicolon > 0 ? value.substring(0, semicolon) : value;
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq).trim();
            String cookieValue = pair.substring(eq + 1).trim();
            if (!name.isEmpty()) {
                cookies.put(name, cookieValue);
            }
        }
        return cookies;
    }
}
