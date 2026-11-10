package top.srcrs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.srcrs.core.SignTask;
import top.srcrs.domain.Config;
import top.srcrs.domain.SignResult;
import top.srcrs.task.EnshanTask;
import top.srcrs.task.JingyiTask;
import top.srcrs.task.KanxueTask;
import top.srcrs.task.TiebaTask;
import top.srcrs.util.PushPlus;

import java.util.ArrayList;
import java.util.List;

/**
 * 程序运行开始的地方。
 *
 * <p>按配置依次执行各站点签到，最后汇总结果并通过 PushPlus 推送。</p>
 *
 * @author srcrs
 */
public class Run {

    private static final Logger LOGGER = LoggerFactory.getLogger(Run.class);

    public static void main(String[] args) {
        Config config = Config.load(args);

        List<SignTask> tasks = buildTasks(config);
        if (tasks.isEmpty()) {
            LOGGER.warn("没有配置任何站点，请至少配置 BDUSS / JINGYI_COOKIE / KANXUE_COOKIE / ENSHAN_COOKIE 之一");
            return;
        }

        List<SignResult> results = new ArrayList<>();
        for (SignTask task : tasks) {
            LOGGER.info("---------- {} 开始签到 ----------", task.getName());
            long start = System.currentTimeMillis();
            SignResult result;
            try {
                result = task.run();
            } catch (Throwable t) {
                LOGGER.error("{} 签到出现未捕获异常 -- {}", task.getName(), t.toString());
                result = SignResult.fail(task.getName(), "未捕获异常：" + t);
            }
            LOGGER.info("{}（耗时 {} 秒）", result.toLine(), (System.currentTimeMillis() - start) / 1000);
            results.add(result);
        }

        String summary = buildSummary(results);
        LOGGER.info("\n{}", summary);

        if (config.hasPush()) {
            PushPlus.send(config.getPushToken(), buildTitle(results), summary);
        }
    }

    /**
     * 根据配置组装需要执行的签到任务
     */
    private static List<SignTask> buildTasks(Config config) {
        List<SignTask> tasks = new ArrayList<>();
        if (config.hasTieba()) {
            tasks.add(new TiebaTask(config.getBduss()));
        }
        if (config.hasJingyi()) {
            tasks.add(new JingyiTask(config.getJingyiCookie(), config.getJingyiEmotion()));
        }
        if (config.hasKanxue()) {
            tasks.add(new KanxueTask(config.getKanxueCookie(), config.getKanxueCsrf()));
        }
        if (config.hasEnshan()) {
            tasks.add(new EnshanTask(config.getEnshanCookie()));
        }
        return tasks;
    }

    /**
     * 汇总文本，日志和推送共用
     */
    private static String buildSummary(List<SignResult> results) {
        int ok = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("========== 签到结果 ==========\n");
        for (SignResult result : results) {
            if (result.isOk()) {
                ok++;
            }
            sb.append(result.toLine()).append('\n');
        }
        sb.append("------------------------------\n");
        sb.append("共 ").append(results.size()).append(" 个站点，正常 ").append(ok)
                .append("，失败 ").append(results.size() - ok);
        return sb.toString();
    }

    private static String buildTitle(List<SignResult> results) {
        int ok = 0;
        for (SignResult result : results) {
            if (result.isOk()) {
                ok++;
            }
        }
        if (ok == results.size()) {
            return "签到成功 " + ok + "/" + results.size();
        }
        return ok == 0 ? "签到全部失败 0/" + results.size() : "签到部分失败 " + ok + "/" + results.size();
    }
}
