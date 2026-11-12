package top.srcrs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 程序运行开始的地方。
 *
 * <p>各站点互不依赖，因此并发执行：贴吧签到大部分时间花在轮次之间的等待上，
 * 让论坛站点同时跑，整体耗时基本等于贴吧自身的耗时。</p>
 *
 * @author srcrs
 */
public class Run {

    private static final Logger LOGGER = LoggerFactory.getLogger(Run.class);

    /** 单个站点最长执行时间，超过则取消并记为超时 */
    private static final long TASK_TIMEOUT_MINUTES = 45;

    public static void main(String[] args) {
        Config config = Config.load(args);

        List<SignTask> tasks = buildTasks(config);
        if (tasks.isEmpty()) {
            LOGGER.warn("没有配置任何站点，请至少配置 BDUSS / JINGYI_COOKIE / KANXUE_COOKIE / ENSHAN_COOKIE 之一");
            return;
        }

        long start = System.currentTimeMillis();
        LOGGER.info("开始执行 {} 个站点：{}（并发）", tasks.size(), joinNames(tasks));

        List<SignResult> results = executeAll(tasks);

        String summary = buildSummary(results);
        LOGGER.info("\n{}", summary);
        LOGGER.info("总耗时 {} 秒", (System.currentTimeMillis() - start) / 1000);

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
            tasks.add(new JingyiTask(config.getJingyiCookie(), config.getJingyiEmotion(),
                    config.getJingyiFormhash()));
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
     * 并发执行所有任务，返回顺序与传入的任务顺序一致
     */
    private static List<SignResult> executeAll(List<SignTask> tasks) {
        List<SignResult> results = new ArrayList<>();
        // 只有一个站点时没必要开线程池
        if (tasks.size() == 1) {
            results.add(execute(tasks.get(0)));
            return results;
        }

        final AtomicInteger seq = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size(), runnable -> {
            Thread thread = new Thread(runnable, "sign-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Callable<SignResult>> callables = new ArrayList<>();
            for (final SignTask task : tasks) {
                callables.add(() -> execute(task));
            }
            List<Future<SignResult>> futures = pool.invokeAll(callables, TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES);

            for (int i = 0; i < futures.size(); i++) {
                SignTask task = tasks.get(i);
                Future<SignResult> future = futures.get(i);
                if (future.isCancelled()) {
                    LOGGER.error("[{}] 执行超过 {} 分钟，已取消", task.getName(), TASK_TIMEOUT_MINUTES);
                    results.add(SignResult.fail(task.getName(),
                            "执行超时（超过 " + TASK_TIMEOUT_MINUTES + " 分钟）"));
                    continue;
                }
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    results.add(SignResult.fail(task.getName(), "执行异常：" + e));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("签到任务被中断 -- {}", e.toString());
            for (SignTask task : tasks) {
                results.add(SignResult.fail(task.getName(), "执行被中断"));
            }
        } finally {
            pool.shutdownNow();
        }
        return results;
    }

    /**
     * 执行单个站点，日志通过 MDC 带上站点名，便于并发时区分来源
     */
    private static SignResult execute(SignTask task) {
        MDC.put("site", task.getName());
        long start = System.currentTimeMillis();
        LOGGER.info("开始签到");
        SignResult result;
        try {
            result = task.run();
        } catch (Throwable t) {
            LOGGER.error("签到出现未捕获异常 -- {}", t.toString());
            result = SignResult.fail(task.getName(), "未捕获异常：" + t);
        }
        LOGGER.info("{}：{}（耗时 {} 秒）", result.getState().getLabel(), result.getMessage(),
                (System.currentTimeMillis() - start) / 1000);
        MDC.remove("site");
        return result;
    }

    private static String joinNames(List<SignTask> tasks) {
        StringBuilder sb = new StringBuilder();
        for (SignTask task : tasks) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(task.getName());
        }
        return sb.toString();
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
