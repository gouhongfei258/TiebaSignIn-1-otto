package top.srcrs.util;

import org.apache.http.Header;
import org.apache.http.HttpConnection;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpInetConnection;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.SystemDefaultDnsResolver;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用 HTTP 工具类，供各论坛签到使用
 *
 * @author srcrs
 */
public final class Http {

    private static final Logger LOGGER = LoggerFactory.getLogger(Http.class);

    /** 连接与读取超时（毫秒） */
    private static final int TIMEOUT = 30_000;

    /** 连接层失败时的总尝试次数（含首次） */
    private static final int MAX_ATTEMPTS = 5;

    /** 重试的初始等待时间（毫秒），之后按 2、4、8… 翻倍 */
    private static final long RETRY_BACKOFF_MS = 2_000L;

    /** 单次重试的最长等待时间（毫秒） */
    private static final long MAX_BACKOFF_MS = 16_000L;

    /** 重定向最多跟随的跳数 */
    private static final int MAX_REDIRECTS = 5;

    /** 连接层失败时统一使用的状态码 */
    private static final int STATUS_NETWORK_ERROR = -1;

    /**
     * 可选代理，来自环境变量 SIGN_PROXY（形如 host:port 或 http://host:port）。
     * 用于 GitHub Actions 出口 IP 被站点拦截时走中转，不配置则为 null，行为与之前一致。
     */
    private static final HttpHost PROXY = resolveProxy();

    /**
     * 可选的 DNS 覆盖，来自环境变量 SIGN_DNS，格式 host=ip，多个用逗号分隔。
     * 用于站点某些节点异常时指定走哪个 IP，SNI 与 Host 仍然是域名，证书校验不受影响。
     */
    private static final Map<String, String> DNS_OVERRIDE = resolveDnsOverride();

    static {
        // JVM 默认会缓存 DNS 结果，重试时可能一直连同一个已经失效的 IP。
        // 置零后每次请求都重新解析，代价是几毫秒，对签到这种低频场景可以忽略。
        try {
            java.security.Security.setProperty("networkaddress.cache.ttl", "0");
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");
        } catch (Exception e) {
            LOGGER.debug("设置 DNS 缓存策略失败，忽略 -- {}", e.toString());
        }
    }

    private Http() {
    }

    private static HttpHost resolveProxy() {
        String value = System.getenv("SIGN_PROXY");
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String raw = value.trim();
        if (raw.contains("://")) {
            raw = raw.substring(raw.indexOf("://") + 3);
        }
        if (raw.contains("@")) {
            LOGGER.warn("SIGN_PROXY 中的认证信息暂不支持，已忽略用户名密码部分");
            raw = raw.substring(raw.lastIndexOf('@') + 1);
        }
        int colon = raw.lastIndexOf(':');
        if (colon <= 0) {
            LOGGER.warn("SIGN_PROXY 格式不正确（应形如 127.0.0.1:7890），已忽略：{}", value);
            return null;
        }
        String host = raw.substring(0, colon);
        try {
            int port = Integer.parseInt(raw.substring(colon + 1).replaceAll("[^0-9].*$", ""));
            LOGGER.info("已启用代理 {}:{}", host, port);
            return new HttpHost(host, port);
        } catch (NumberFormatException e) {
            LOGGER.warn("SIGN_PROXY 端口不是数字，已忽略：{}", value);
            return null;
        }
    }

    private static Map<String, String> resolveDnsOverride() {
        Map<String, String> map = new LinkedHashMap<>();
        String value = System.getenv("SIGN_DNS");
        if (value == null || value.trim().isEmpty()) {
            return map;
        }
        for (String item : value.split(",")) {
            int eq = item.indexOf('=');
            if (eq > 0) {
                map.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
            }
        }
        if (!map.isEmpty()) {
            LOGGER.info("已启用 DNS 覆盖：{}", map);
        }
        return map;
    }

    /**
     * 简易响应对象
     */
    public static class Resp {
        private final int status;
        private final String body;
        private final Map<String, String> cookies;
        /** 连接层失败的原因，正常响应时为 null */
        private final String error;

        Resp(int status, String body, Map<String, String> cookies) {
            this(status, body, cookies, null);
        }

        Resp(int status, String body, Map<String, String> cookies, String error) {
            this.status = status;
            this.body = body;
            this.cookies = cookies;
            this.error = error;
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

        public String getError() {
            return error;
        }

        public boolean isOk() {
            return status >= 200 && status < 400;
        }

        /**
         * 供失败信息使用的一句话描述，例如「HTTP 502」「连接超时（30 秒内未建立连接）」
         */
        public String describe() {
            if (status > 0) {
                return "HTTP " + status;
            }
            return error == null ? "网络请求失败（原因未知）" : error;
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

    /**
     * 执行请求，连接层失败（超时、DNS、TLS、连接重置）时自动重试。
     * GitHub Actions 的出口网络访问国内站点经常抖动，单次失败直接判定签到失败太草率。
     */
    private static Resp execute(HttpRequestBase request) {
        Resp resp = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            resp = attempt(request);
            if (resp.getStatus() != STATUS_NETWORK_ERROR) {
                return resp;
            }
            if (attempt < MAX_ATTEMPTS) {
                long backoff = Math.min(RETRY_BACKOFF_MS * (1L << (attempt - 1)), MAX_BACKOFF_MS);
                LOGGER.warn("{} 第 {}/{} 次请求失败：{}，{} 秒后重试",
                        request.getURI(), attempt, MAX_ATTEMPTS, resp.describe(), backoff / 1000);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return resp;
                }
            }
        }
        LOGGER.error("{} 连续 {} 次请求失败，放弃：{}", request.getURI(), MAX_ATTEMPTS,
                resp == null ? "未知错误" : resp.describe());
        String host = request.getURI().getHost();
        if (host != null) {
            LOGGER.error("{} 的 DNS 解析结果：{}", host, resolveHint(host));
        }
        return resp;
    }

    /**
     * 打印域名解析到的 IP，用于判断是解析异常还是连不通
     */
    private static String resolveHint(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            StringBuilder sb = new StringBuilder();
            for (InetAddress address : addresses) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(address.getHostAddress());
            }
            return sb.length() == 0 ? "无记录" : sb.toString();
        } catch (Exception e) {
            return "解析失败（" + e.getClass().getSimpleName() + "）";
        }
    }

    private static Resp attempt(HttpRequestBase request) {
        RequestConfig.Builder configBuilder = RequestConfig.custom()
                .setConnectTimeout(TIMEOUT)
                .setSocketTimeout(TIMEOUT)
                .setConnectionRequestTimeout(TIMEOUT);
        if (PROXY != null) {
            configBuilder.setProxy(PROXY);
        }
        HttpClientBuilder builder = HttpClients.custom()
                .setDefaultRequestConfig(configBuilder.build())
                // 关闭内置 Cookie 管理，统一由各任务自己维护 Cookie 头
                .disableCookieManagement()
                // 重定向由自己处理：站点常用「302 回自身 + 下发新 Cookie」的 WAF 挑战，
                // HttpClient 自动跟随时不带新 Cookie，会判定为循环并抛 CircularRedirectException
                .disableRedirectHandling();
        if (!DNS_OVERRIDE.isEmpty()) {
            builder.setDnsResolver(host -> {
                String ip = DNS_OVERRIDE.get(host);
                if (ip != null) {
                    try {
                        return new InetAddress[]{InetAddress.getByName(ip)};
                    } catch (Exception e) {
                        LOGGER.warn("SIGN_DNS 指定的 IP 无法使用（{}={}），改用系统解析", host, ip);
                    }
                }
                return SystemDefaultDnsResolver.INSTANCE.resolve(host);
            });
        }
        CloseableHttpClient client = builder.build();
        long start = System.currentTimeMillis();
        try {
            return follow(client, request, start);
        } catch (Exception e) {
            String reason = describeException(e);
            LOGGER.warn("请求异常 {} -- {}（耗时 {} ms）", request.getURI(), reason,
                    System.currentTimeMillis() - start);
            return new Resp(STATUS_NETWORK_ERROR, null, new LinkedHashMap<String, String>(), reason);
        } finally {
            try {
                client.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }

    /**
     * 手动跟随重定向，每一跳都把响应里的 Set-Cookie 合并进下一次请求。
     * 这是「302 回自身 + 下发 Cookie」这类 WAF 挑战能走出去的关键：
     * 不带新 Cookie 的话，服务器会一直重定向回原地址。
     */
    private static Resp follow(CloseableHttpClient client, HttpRequestBase request, long start)
            throws IOException {
        // POST 等带 body 的请求不跟随，避免表单被重复提交到重定向目标
        boolean followRedirect = !(request instanceof HttpEntityEnclosingRequest);
        Map<String, String> cookies = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        List<String> chain = new ArrayList<>();
        visited.add(request.getURI().toString());
        chain.add(request.getURI().toString());
        HttpRequestBase current = request;

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpClientContext context = HttpClientContext.create();
            CloseableHttpResponse response = client.execute(current, context);
            try {
                int status = response.getStatusLine().getStatusCode();
                String remote = remoteAddress(context);
                Map<String, String> received = parseCookies(response.getHeaders("Set-Cookie"));
                boolean cookieChanged = hasNewCookie(cookies, received);
                cookies.putAll(received);
                String location = firstHeader(response, "Location");
                // 响应体只能读一次，先读出来再决定怎么走
                String body = readBody(response.getEntity());

                if (!followRedirect || !isRedirect(status) || location == null) {
                    LOGGER.debug("{} {} -> {}（{} ms，远端 {}）", current.getMethod(), current.getURI(),
                            status, System.currentTimeMillis() - start, remote);
                    return new Resp(status, body, cookies);
                }
                if (hop >= MAX_REDIRECTS) {
                    LOGGER.warn("{} 重定向超过 {} 次，停止跟随：{}", request.getURI(), MAX_REDIRECTS,
                            String.join(" -> ", chain));
                    return new Resp(status, body, cookies);
                }

                String next = absolute(current.getURI().toString(), location);
                boolean seen = !visited.add(next);
                if (seen && !cookieChanged) {
                    // 回到同一个地址又没有新 Cookie，再跟下去只会死循环
                    LOGGER.warn("检测到重定向循环（{}），已停止跟随", next);
                    LOGGER.warn("  跳转过程：{}", String.join(" -> ", chain));
                    LOGGER.warn("  最后一次响应：状态 {}，远端 {}，Location {}，Set-Cookie {}",
                            status, remote, location, received.isEmpty() ? "无" : received.toString());
                    LOGGER.warn("  响应内容片段：{}", preview(body));
                    return new Resp(status, body, cookies);
                }
                LOGGER.debug("跟随重定向 {} -> {}（状态 {}，下发新 Cookie：{}）",
                        current.getURI(), next, status, cookieChanged);
                chain.add(next);
                current = rebuildGet(current, next, cookies);
            } finally {
                response.close();
            }
        }
        return new Resp(STATUS_NETWORK_ERROR, null, cookies, "重定向次数过多");
    }

    private static HttpRequestBase rebuildGet(HttpRequestBase from, String url,
                                              Map<String, String> cookies) {
        HttpGet get = new HttpGet(url);
        for (Header header : from.getAllHeaders()) {
            if ("Cookie".equalsIgnoreCase(header.getName())) {
                continue;
            }
            get.setHeader(header.getName(), header.getValue());
        }
        String cookie = mergeCookieHeader(from.getFirstHeader("Cookie"), cookies);
        if (cookie != null && !cookie.isEmpty()) {
            get.setHeader("Cookie", cookie);
        }
        return get;
    }

    /**
     * 把原有的 Cookie 头与响应下发的新 Cookie 合并成一条 Cookie 头。
     * 用户显式传入的 Cookie 优先：Discuz 的 auth 是用 saltkey 加盐算出来的，
     * 服务器在重定向时若下发新的 saltkey，直接覆盖会让 auth 与 saltkey 不匹配，登录态当场失效。
     */
    private static String mergeCookieHeader(Header original, Map<String, String> cookies) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.putAll(cookies);
        if (original != null && original.getValue() != null) {
            for (String part : original.getValue().split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    merged.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
                }
            }
        }
        if (merged.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    private static boolean hasNewCookie(Map<String, String> existing, Map<String, String> received) {
        for (Map.Entry<String, String> entry : received.entrySet()) {
            String old = existing.get(entry.getKey());
            if (old == null || !old.equals(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static String firstHeader(HttpResponse response, String name) {
        Header header = response.getFirstHeader(name);
        return header == null ? null : header.getValue();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String absolute(String base, String location) {
        try {
            return new URI(base).resolve(location).toString();
        } catch (Exception e) {
            return location;
        }
    }

    /**
     * 截取响应内容片段，用于日志里看清站点到底返回了什么
     */
    private static String preview(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "（空）";
        }
        String text = body.replaceAll("\\s+", " ").trim();
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    /**
     * 取本次请求实际连接的远端地址，用于确认 Actions 解析到的是哪个节点
     */
    private static String remoteAddress(HttpClientContext context) {
        try {
            HttpConnection connection = context.getConnection();
            if (connection instanceof HttpInetConnection) {
                HttpInetConnection inet = (HttpInetConnection) connection;
                InetAddress address = inet.getRemoteAddress();
                if (address != null) {
                    return address.getHostAddress() + ":" + inet.getRemotePort();
                }
            }
        } catch (Exception ignored) {
            // 拿不到就算了，不影响请求本身
        }
        return "未知";
    }

    /**
     * 把底层异常翻译成能直接看懂的失败原因。
     * 原来的实现只回一个 HTTP -1，分不清是超时、DNS 还是 TLS 问题。
     */
    private static String describeException(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        int seconds = TIMEOUT / 1000;

        if (cause instanceof SocketTimeoutException) {
            String lower = msg == null ? "" : msg.toLowerCase();
            if (lower.contains("connect")) {
                return "连接超时（" + seconds + " 秒内未能建立连接，通常是网络不通或站点限制了该地区访问）";
            }
            return "读取超时（" + seconds + " 秒内未收到响应）";
        }
        if (cause instanceof UnknownHostException) {
            return "域名解析失败（" + msg + "）";
        }
        if (cause instanceof SSLException) {
            return "TLS 握手失败（" + msg + "）";
        }
        if (cause instanceof NoRouteToHostException) {
            return "网络不可达（" + msg + "）";
        }
        if (cause instanceof ConnectException) {
            return "连接被拒绝或重置（" + msg + "）";
        }
        if (cause instanceof SocketException) {
            return "连接被中断（" + msg + "）";
        }
        return cause.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
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
