<div align="center"> 
<h1 align="center">多站签到助手（贴吧 / 精易 / 看雪 / 恩山）</h1>
<img src="https://img.shields.io/github/issues/srcrs/TiebaSignIn?color=green">
<img src="https://img.shields.io/github/stars/srcrs/TiebaSignIn?color=yellow">
<img src="https://img.shields.io/github/forks/srcrs/TiebaSignIn?color=orange">
<img src="https://img.shields.io/github/license/srcrs/TiebaSignIn?color=ff69b4">
<img src="https://img.shields.io/github/languages/code-size/srcrs/TiebaSignIn?color=blueviolet">
</div>

# 简介

在原有贴吧签到的基础上，扩展成多站点签到工具：一次运行，依次完成**百度贴吧、精易论坛、看雪论坛、恩山论坛**的签到，并把汇总结果推送到微信。

所有站点都只需要填写浏览器里已有的登录凭证（`BDUSS` 或 `Cookie`），不需要账号密码，也不需要打码平台。

# 支持站点

| 站点 | 需要的凭证 | 签到方式 |
|-|-|-|
| 百度贴吧 | `BDUSS` | 手机端接口，最多 200 个贴吧 |
| 精易论坛 `bbs.ijingyi.com` | `JINGYI_COOKIE` | Discuz + dsu_paulsign 签到插件 |
| 看雪论坛 `bbs.kanxue.com` | `KANXUE_COOKIE`（可选 `KANXUE_CSRF`） | `user-signin.htm` 接口 |
| 恩山论坛 `www.right.com.cn` | `ENSHAN_COOKIE` | Discuz + erling_qd 签到插件（含 WAF 处理） |

只配置其中一部分也可以，未配置的站点会自动跳过。

# 使用方法

## 1. fork 本项目

### 必须检查的仓库设置

1. Settings -> Actions -> General -> Workflow permissions：选择 "Read and write permissions"（以允许 GITHUB_TOKEN push）。

2. 确保仓库没有被 Archived（Settings -> General -> Danger Zone: Archive repository）。

## 2. 准备各站点凭证

### 百度贴吧 BDUSS

在网页中登录上贴吧，然后按下 `F12` 打开调试模式，在 `cookie` 中找到 `BDUSS`，并复制其 `Value` 值。

![](./assets/获取BDUSS.gif)

### 精易论坛 Cookie

浏览器登录 `https://bbs.ijingyi.com/`，`F12` -> Network -> 任意请求的 Request Headers，复制整条 `Cookie`。

> 站点登录需要过极验滑块，所以这里不做账号密码登录，直接复用浏览器里的登录态。
> 只要页面右上角还能看到「退出」，Cookie 就是有效的。

### 看雪论坛 Cookie

浏览器登录 `https://bbs.kanxue.com/`，同样复制整条 `Cookie`（关键是 `bbs_sid`、`bbs_token` 等）。

`csrf_token` 一般是自动获取的：签到接口会自己读页面。如果推送提示 `csrf_token 校验失败`，再手动补一个：

1. 登录后打开签到页面，`F12` -> Elements，搜索 `csrf_token`；
2. 把它的值填到 Secrets 的 `KANXUE_CSRF`。

### 恩山论坛 Cookie

浏览器登录 `https://www.right.com.cn/forum/`，复制整条 `Cookie`（关键是 Discuz 的 `xxxx_auth`、`xxxx_saltkey`、`xxxx_sid`）。

## 3. 将凭证添加到仓库的 Secrets 中

Settings -> Secrets and variables -> Actions -> New repository secret：

Name | 必填 | 说明
-|-|-
`BDUSS` | 否 | 贴吧 BDUSS
`JINGYI_COOKIE` | 否 | 精易论坛 Cookie
`JINGYI_EMOTION` | 否 | 签到心情，默认 `kx`(开心)，可选 `ng`/`ym`/`wl`/`nu`/`ch`/`fd`/`yl`/`shuai`
`KANXUE_COOKIE` | 否 | 看雪论坛 Cookie
`KANXUE_CSRF` | 否 | 看雪 csrf_token，一般留空
`ENSHAN_COOKIE` | 否 | 恩山论坛 Cookie
`SCKEY` | 否 | PushPlus 的 token，用于微信推送
`SIGN_PROXY` | 否 | 论坛请求走的代理，形如 `host:port`。Actions 出口 IP 被站点拦截时才需要
`SIGN_DNS` | 否 | 指定域名走哪个 IP，形如 `bbs.ijingyi.com=47.111.220.60`，多个用逗号分隔

至少配置一个站点，否则程序只会打印一行提示后退出。

## 4. 开启 actions

默认 `actions` 是处于禁止的状态，需要手动开启。

![](./assets/开启actions.gif)

## 5. 第一次运行 actions

+ 自己提交一次 `push`。

将 `run.txt` 中的 `flag` 由 `0` 改为 `1`

```patch
- flag: 0
+ flag: 1
```

![](./assets/运行结果.gif)

## 成功了

每天早上 `6:30` 将会自动进行签到。

# 本地运行

```shell
# 方式一：环境变量
export BDUSS=xxxx
export JINGYI_COOKIE="xxxx=1; yyyy=2"
export KANXUE_COOKIE="xxxx=1; yyyy=2"
export ENSHAN_COOKIE="xxxx=1; yyyy=2"
export SCKEY=xxxx
mvn -B compile exec:java -Dexec.mainClass="top.srcrs.Run"
```

也支持命令行直接传参（两种写法可以混用，`key=value` 会覆盖环境变量）：

```shell
# 兼容旧版的写法：第一个参数 BDUSS，第二个参数 SCKEY
mvn -B compile exec:java -Dexec.mainClass="top.srcrs.Run" -Dexec.args="xxxx sckey"

# 键值写法
mvn -B compile exec:java -Dexec.mainClass="top.srcrs.Run" \
  -Dexec.args="bduss=xxxx jingyi=xxxx kanxue=xxxx csrf=xxxx enshan=xxxx sckey=xxxx"
```

可用的键：`bduss`、`jingyi`、`emotion`、`kanxue`、`csrf`、`enshan`、`sckey`。

# 输出示例

```
---------- 百度贴吧 开始签到 ----------
-----第 1 轮签到开始-----
还剩 12 贴吧需要签到
...
[成功] 百度贴吧：共 12 个贴吧，成功 12，失败 0（耗时 68 秒）
---------- 精易论坛 开始签到 ----------
[成功] 精易论坛：签到成功，获得 5 精币，连续 7 天（耗时 3 秒）
---------- 看雪论坛 开始签到 ----------
[已签到] 看雪论坛：今天已经签过了（耗时 1 秒）
---------- 恩山论坛 开始签到 ----------
[成功] 恩山论坛：签到成功，获得 3 积分（耗时 2 秒）

========== 签到结果 ==========
[成功] 百度贴吧：共 12 个贴吧，成功 12，失败 0
[成功] 精易论坛：签到成功，获得 5 精币，连续 7 天
[已签到] 看雪论坛：今天已经签过了
[成功] 恩山论坛：签到成功，获得 3 积分
------------------------------
共 4 个站点，正常 4，失败 0
```

# 注意事项

+ **PushPlus 现在要求实名认证**。未实名的账号调用发送接口会返回 `905`，日志里会打印
  「账号未实名认证，需先在 pushplus 个人中心完成认证才能发消息」，去认证一下即可。
  免费额度约每天 5 条，一天一条签到汇总够用。其它常见返回码：`903` token 不对、
  `900` 请求过于频繁被临时限制、`403` 开放接口 IP 未授权。

+ **Cookie 会过期**，一般几周到几个月。推送里出现「Cookie 已失效」时，重新复制一次即可。

+ **网络类失败会有明确原因**。以前只显示 `HTTP -1`，分不清是超时还是站点拒绝，现在会写成
  「连接超时（30 秒内未能建立连接…）」「域名解析失败」「TLS 握手失败」「连接被拒绝或重置」等，
  并且连接层失败会自动重试 5 次（间隔 2、4、8、16 秒，总计约 30 秒），
  每次重试都会重新解析 DNS，避免一直连同一个失效 IP。连续失败时会打印该域名解析到的所有 IP。

+ **Actions 出口 IP 可能被国内论坛拦截**。GitHub 的 runner 用的是 Azure 机房 IP，
  国内部分论坛会对这类 IP 直接断连或丢包，表现为「访问首页失败：连接超时 / 连接被重置」。
  workflow 里的 `Check network reachability` 步骤会打印出口 IP、各站点状态码与 curl 退出码，
  用来区分「IP 被拦」和「代码问题」：
  - 百度可达、只有某个论坛不可达 → 该站点针对境外/Azure IP 的限制，与代码无关；
  - 返回 `403`/`412` 而不是连接失败 → WAF 规则拦截（不是封 IP），换 UA 或等一段时间；
  - 返回 `301` 且 `Location` 指回自身 → 站点按来源 IP 拒绝，换 Cookie、换 UA、换入口、
    加重试都无效，只能更换出口网络。**精易论坛目前就是这种情况**：对 Azure 段返回
    301 跳回自己（服务端配置问题），因此它在 GitHub Actions 上会一直失败；
  - 确认是 IP 问题后，配置 `SIGN_PROXY` 走中转即可，代码不需要改。

+ **恩山论坛前面有 WAF**。站点可能返回两种拦截页：
  - JS 挑战（写入 `https_ydclearance`）：程序会自己解析并重试，不需要处理；
  - 阿里云滑块验证：无法在无头环境通过，推送里会提示「被站点 WAF 拦截」，换个网络环境或稍后重试。

+ **重定向由程序自己跟随**。部分站点的 WAF 会用「302 回原地址 + 下发 Cookie」的方式做挑战，
  交给 HttpClient 自动跟随会被判定为循环并抛 `CircularRedirectException`。现在每一跳都会把
  响应里的 `Set-Cookie` 合并进下一次请求，带上 Cookie 重试后通常能直接通过；确实走不出去时
  也不会抛异常，而是明确报「站点返回 302 重定向且未能跳转成功，多半是 WAF 挑战未通过」。

+ 贴吧签到失败时程序会自动重试 5 轮（每轮间隔 5 分钟），所以贴吧任务最坏情况下要跑 20 多分钟，这是原有逻辑。

+ **四个站点是并发执行的**，整体耗时约等于最慢的那个（通常就是贴吧）。并发日志每行前面带 `[站点名]` 前缀，方便区分来源；贴吧在轮次之间等待时只有它自己静默，其它站点早就跑完了。

+ 站点返回的文案会被原样带进推送，如果某个站点改版，看日志里对应的 `formhash` / `csrf_token` 那几行最容易定位问题。

# 目录结构

```
src/main/java/top/srcrs
├── Run.java                    程序入口：组装任务 -> 依次执行 -> 汇总推送
├── core/SignTask.java          签到任务接口
├── domain
│   ├── Config.java             环境变量 / 命令行参数
│   ├── Cookie.java             贴吧 BDUSS 容器
│   └── SignResult.java         单站点签到结果
├── task
│   ├── TiebaTask.java          百度贴吧
│   ├── JingyiTask.java         精易论坛
│   ├── KanxueTask.java         看雪论坛
│   └── EnshanTask.java         恩山论坛
└── util
    ├── EnshanWaf.java          恩山 WAF 的 JS 挑战解析
    ├── Html.java               formhash / 提示信息提取
    ├── Http.java               通用 HTTP 封装
    ├── Cookies.java            Cookie 解析与合并
    ├── PushPlus.java           微信推送
    ├── Request.java            贴吧专用请求（旧）
    └── Encryption.java         MD5
```

# 更新日志

## 2026-11-10

+ 新增精易论坛、看雪论坛、恩山论坛签到

+ 各站点改为并发执行，贴吧那 20 多分钟的等待期间论坛站点已经跑完，总耗时不再累加

+ 重构为「任务」结构：每个站点一个 `SignTask` 实现，`Run` 只负责调度与汇总

+ 配置改为从环境变量读取，`Run` 的旧版位置参数仍然兼容

+ 修复 `slf4j-api 1.7` 与 `logback 1.3` 版本不匹配导致日志完全不输出的问题

+ 修复贴吧请求没有设置超时、socket 卡住会无限等待的问题

+ 新增 `logback.xml`，默认 INFO 级别，日志带站点名前缀

## 2020-11-01

+ 代码重构

+ 修改签到策略

大大提高一次运行，贴吧签到的成功率，基本很少的贴吧会签到失败。

+ 去除多用户的支持

+ 增加支持 server酱推送，可以推送至微信

## 2020-10-19

~~增加支持多账户签到，每个账号的`BDUSS`使用`&&`分割，具体格式如下。~~
