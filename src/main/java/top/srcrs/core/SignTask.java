package top.srcrs.core;

import top.srcrs.domain.SignResult;

/**
 * 签到任务接口，每一个站点实现一个任务
 *
 * @author srcrs
 */
public interface SignTask {

    /**
     * 站点名称，用于日志和推送
     */
    String getName();

    /**
     * 执行签到，实现类不允许抛出异常，内部自行捕获并返回失败结果
     */
    SignResult run();
}
