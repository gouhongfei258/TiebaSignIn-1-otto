package top.srcrs.domain;

/**
 * 单个站点的签到结果
 *
 * @author srcrs
 */
public class SignResult {

    /**
     * 签到状态
     */
    public enum State {
        /** 签到成功 */
        SUCCESS("成功"),
        /** 今日已经签过，视为正常 */
        ALREADY("已签到"),
        /** 签到失败 */
        FAILED("失败");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private final String site;
    private final State state;
    private final String message;

    private SignResult(String site, State state, String message) {
        this.site = site;
        this.state = state;
        this.message = message;
    }

    public static SignResult success(String site, String message) {
        return new SignResult(site, State.SUCCESS, message);
    }

    public static SignResult already(String site, String message) {
        return new SignResult(site, State.ALREADY, message);
    }

    public static SignResult fail(String site, String message) {
        return new SignResult(site, State.FAILED, message);
    }

    public String getSite() {
        return site;
    }

    public State getState() {
        return state;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 是否算作正常（成功或今日已签到）
     */
    public boolean isOk() {
        return state != State.FAILED;
    }

    /**
     * 单行文本，用于日志与推送
     */
    public String toLine() {
        return "[" + state.getLabel() + "] " + site + "：" + message;
    }
}
