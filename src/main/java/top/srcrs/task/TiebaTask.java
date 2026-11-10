package top.srcrs.task;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.srcrs.core.SignTask;
import top.srcrs.domain.Cookie;
import top.srcrs.domain.SignResult;
import top.srcrs.util.Encryption;
import top.srcrs.util.Request;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 百度贴吧签到（原 Run 中的逻辑迁移而来，行为保持不变）
 *
 * @author srcrs
 */
public class TiebaTask implements SignTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TiebaTask.class);

    /** 获取用户所有关注贴吧 */
    private static final String LIKE_URL = "https://tieba.baidu.com/mo/q/newmoindex";
    /** 获取用户的 tbs */
    private static final String TBS_URL = "http://tieba.baidu.com/dc/common/tbs";
    /** 贴吧签到接口 */
    private static final String SIGN_URL = "http://c.tieba.baidu.com/c/c/forum/sign";

    private final String bduss;

    /** 存储用户所关注的贴吧 */
    private final List<String> follow = new ArrayList<>();
    /** 签到成功的贴吧列表 */
    private final List<String> success = new ArrayList<>();
    /** 签到失败的贴吧列表 */
    private final Set<String> failed = new LinkedHashSet<>();
    /** 失效的贴吧列表 */
    private final List<String> invalid = new ArrayList<>();

    /** 用户的 tbs */
    private String tbs = "";
    /** 用户所关注的贴吧数量 */
    private int followNum = 201;

    public TiebaTask(String bduss) {
        this.bduss = bduss;
    }

    @Override
    public String getName() {
        return "百度贴吧";
    }

    @Override
    public SignResult run() {
        if (bduss == null || bduss.trim().isEmpty()) {
            return SignResult.fail(getName(), "未配置 BDUSS");
        }
        // 存入 Cookie，以备使用
        Cookie.getInstance().setBDUSS(bduss);
        getTbs();
        if (tbs == null || tbs.isEmpty()) {
            return SignResult.fail(getName(), "获取 tbs 失败，BDUSS 可能已失效");
        }
        getFollow();
        runSign();

        int successNum = success.size();
        int failedNum = followNum - successNum;
        LOGGER.info("共 {} 个贴吧 - 成功: {} - 失败: {} - {}", followNum, successNum, failedNum, failed);
        LOGGER.info("失效 {} 个贴吧: {}", invalid.size(), invalid);

        String message = "共 " + followNum + " 个贴吧，成功 " + successNum + "，失败 " + failedNum;
        if (!invalid.isEmpty()) {
            message += "，失效 " + invalid.size();
        }
        if (followNum <= 0) {
            return SignResult.fail(getName(), "获取关注的贴吧列表失败");
        }
        return failed.isEmpty() ? SignResult.success(getName(), message) : SignResult.fail(getName(), message);
    }

    /**
     * 进行登录，获得 tbs，签到的时候需要用到这个参数
     */
    private void getTbs() {
        try {
            JSONObject jsonObject = Request.get(TBS_URL);
            if (jsonObject != null && "1".equals(jsonObject.getString("is_login"))) {
                LOGGER.info("获取tbs成功");
                tbs = jsonObject.getString("tbs");
            } else {
                LOGGER.warn("获取tbs失败 -- {}", jsonObject);
            }
        } catch (Exception e) {
            LOGGER.error("获取tbs部分出现错误 -- {}", e.toString());
        }
    }

    /**
     * 获取用户所关注的贴吧列表
     */
    private void getFollow() {
        try {
            JSONObject jsonObject = Request.get(LIKE_URL);
            JSONArray jsonArray = jsonObject.getJSONObject("data").getJSONArray("like_forum");
            followNum = jsonArray.size();
            LOGGER.info("获取贴吧列表成功");
            for (Object array : jsonArray) {
                JSONObject forum = (JSONObject) array;
                String tiebaName = forum.getString("forum_name");
                if ("0".equals(forum.getString("is_sign"))) {
                    follow.add(tiebaName.replace("+", "%2B"));
                    if (Request.isTiebaNotExist(tiebaName)) {
                        follow.remove(tiebaName.replace("+", "%2B"));
                        invalid.add(tiebaName);
                        failed.add(tiebaName);
                    }
                } else {
                    success.add(tiebaName);
                }
            }
        } catch (Exception e) {
            LOGGER.error("获取贴吧列表部分出现错误 -- {}", e.toString());
            followNum = 0;
        }
    }

    /**
     * 开始进行签到，每一轮将所有未签到的贴吧进行签到，最多 5 轮
     */
    private void runSign() {
        int flag = 5;
        try {
            while (success.size() < followNum && flag > 0) {
                LOGGER.info("-----第 {} 轮签到开始-----", 5 - flag + 1);
                LOGGER.info("还剩 {} 贴吧需要签到", followNum - success.size());
                Iterator<String> iterator = follow.iterator();
                while (iterator.hasNext()) {
                    String s = iterator.next();
                    String rotation = s.replace("%2B", "+");
                    String body = "kw=" + s + "&tbs=" + tbs + "&sign="
                            + Encryption.enCodeMd5("kw=" + rotation + "tbs=" + tbs + "tiebaclient!!!");
                    JSONObject post = Request.post(SIGN_URL, body);
                    int randomTime = new Random().nextInt(200) + 300;
                    LOGGER.info("等待 {} 毫秒", randomTime);
                    TimeUnit.MILLISECONDS.sleep(randomTime);
                    if (post != null && "0".equals(post.getString("error_code"))) {
                        iterator.remove();
                        success.add(rotation);
                        failed.remove(rotation);
                        LOGGER.info("{}: 签到成功", rotation);
                    } else {
                        failed.add(rotation);
                        LOGGER.warn("{}: 签到失败", rotation);
                    }
                }
                if (success.size() != followNum - invalid.size()) {
                    // 为防止短时间内多次请求接口触发风控，每轮结束后等待 5 分钟
                    Thread.sleep(1000 * 60 * 5);
                    // 重新获取 tbs，避免后续轮次全部失败
                    getTbs();
                }
                flag--;
            }
        } catch (Exception e) {
            LOGGER.error("签到部分出现错误 -- {}", e.toString());
        }
    }
}
