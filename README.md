# QQ 每日自动打卡 v1.7（源码与产物汇总）

LSPosed 模块：静默重放 `ti.qq.com` 的每日打卡接口，替你把「我的」页那几张卡（日签卡 / 晚安卡 / 收集卡）打完，成功或当天剩卡都会发通知。关键变化是 **QQ 9.3.75 也能打了**，并且已经跑满 21 天无遗漏。

## 目录

```
QQ自动打卡-v1.7/
├── module/           模块源码（v1.7，versionCode 11）：src / stubs / res / assets / test / build.sh
├── probe/            抓包探针源码（含由 sync_probe.py 生成的引擎副本 Auto*.java）
├── dist/
│   ├── QQ每日自动打卡-v1.7.apk   ← 装这个
│   ├── QQSignAuto.apk            （同一份字节）
│   └── QQSignProbe.apk           探针，只在需要抓包/自检通知时用
├── sync_probe.py     把 module 的 Card/Session/SignInClient/Notifier 改名生成 probe 副本
├── sdk.sh            两个 build.sh 共用的 Android SDK 定位（d8/aapt2/zipalign/apksigner + android.jar）
└── evidence/
    ├── 运行日志-多日实录.txt      手机上的模块日志全文（2026-10-24 → 11-13，592 行）
    ├── 实测日志-QQ9.3.75.txt      打通当天的两段 logcat
    ├── 打卡提醒实测-裁切.png      HyperOS 通知栏实图（挂在 QQ 图标下）
    └── 反编译证据/                决定路线的 6 个反编译文件
```

`module/`、`probe/` 与 `work/checkin/auto`、`work/checkin/module` 逐字节一致（`diff -r` 只差 `work/` 里跑测试留下的 `test/com/*.class`，交付副本刻意不带），`dist/` 里的 APK 与当场构建产物 `cmp` 通过；这里是一份干净快照，`work/` 里那份仍是可以继续开发的原地。

## 改了什么，为什么

9.2.65 的老路子是读 X5 cookie 罐。9.3.75 上主进程里三个罐全读不出来（`smtt=null`、`xweb` 抛 `InvocationTargetError`、系统罐 `null`），手动打开打卡页也救不回来，所以新增第二条身份来源：QQ 自己的票据服务 `Session.java`。

三条硬结论，都有反编译 + 真机双重证据：

1. **`getSkey` / `getRealSkey` 是诱饵**。两者都落到 `NtTicketProvider.getSkey()` → `SigInjectManager.getFakeSkey()` → `com.tencent.qqconnect.wtlogin.e#getFakeSkey()`，没被热补丁时返回字面量 `"MMMMM"`（正好 5 字符）。用它算 bkn 必被服务端拒。
2. **能用的是按域名的 pskey**。`TicketManagerImpl.getPskey(uin,"ti.qq.com")` 给 44 字符，把它同时塞进 `skey` 与 `p_skey`、`bkn = gtk(它)`，`SignInMainPage` 直接 `retCode:0`。先例是 QQ 会员那套 `VasSkey`——按域名取 pskey 当钥匙，不碰 skey。`getPskey(uin,"qq.com")` 会卡在没超时的 IPC 闩上，所以这类调用统一丢线程 `join(8s)`。
3. **`SignIn` 的 `retCode:2` = 还没到点**（09:58 打晚安卡实测），不是失败。模块据此按小时退避，20:00 后自动补打。

顺带：多个候选身份用只读的 `SignInMainPage` 逐个探测，服务端认哪个就用哪个；票据结果缓存 10 分钟，5 分钟一轮的 tick 不会反复打 IPC；打卡线程在拿到 Application 上下文时清掉 `nextAt`（旧的退避跨进程无意义）；tick 间隔 20 分钟缩到 5 分钟（一轮只是一次 SharedPreferences 读取）；retCode 全中文化；通知改为「今日自动打卡：日签卡、收集卡、晚安卡」这种当天累计清单（跨轮次记在 SharedPreferences 的 `signed`，每日零点重置）。

隐私边界保持不动：日志只出现 cookie 名称与密钥长度，从不写值。

## 实测结果：21 天连续运行

手机上的模块日志全文已存进 `evidence/运行日志-多日实录.txt`（592 行），从它自己的记录看：

- 覆盖 2026-10-24 → 11-13 共 21 个自然日，**21 天全部「打卡完成」，一天没漏**；10-24 是唯一一段调试期（09:0x–09:58 还没走通 pskey），当天 20:29 补上晚安卡后，从 10-25 起每天都是 `已打卡：日签卡、晚安卡、收集卡`。
- **成功通知 21 条**（每天 1 条，走 `qqsign_auto_result` 渠道），**失败提醒 0 条**（`qqsign_auto_alert` 一次没触发），**崩溃/异常 0 条**。
- 打卡时刻基本卡在 00:00–00:08，说明三张卡在新的一天零点就已全部可打（晚安卡并不非要等到 20:00——它当天 09:58 给 `retCode:2`，但 00:02 给 0）。只有 11-02（03:16）、11-09（01:15）推迟了，那两天 QQ 进程在零点没活着，起来后立刻补上——正好印证「调度跟着 QQ 进程走」这条边界。
- 身份来源 21 天里 39 次成功命中 `pskey(ti.qq.com)`，没有一次落到别的候选；`getSkey`/`getRealSkey` 每次都还是 5 字符诱饵，结论稳定。

v1.7 就是照这份统计做的一个删减：`getPskey(uin,"qq.com")` 在 69 次猎取里 **全部超时**，而 ti.qq.com 的钥匙每次都被接受，所以这个候选已从 `Session.hunt()` 去掉——省掉每轮 8 秒等待和一个永久卡在 QQ 无超时 IPC 闩上的线程，能力没有任何损失（注释里写清了「若哪天 ti.qq.com 被拒再补回来」）。

## 安装

首次安装直接用 `dist/QQ每日自动打卡-v1.7.apk`。**装过旧版（`com.hou33.qqsignauto` + debug 签名）的必须先卸载**：新包的 applicationId 已改为 `io.github.zakoalice.qqsignauto`，签名也换成了专用 release 密钥，Android 不认作同一个应用，直接覆盖会报「应用未安装 / 签名冲突」。装完在 LSPosed 里重新勾选作用域 `com.tencent.mobileqq` 主进程，然后 QQ 上「强行停止 → 重新打开」。`qqsign_auto.cfg` 可选配置 `enabled=0` 关掉、`hour=N` 推迟首次尝试的小时。

## 构建

```
cd module && bash build.sh        # 产出 build/QQSignAuto.apk
cd .. && python sync_probe.py     # 改完 module 必须重跑，否则 probe 副本会失效
cd probe && bash build.sh
```

工具链路径以前写死成 `D:\build-tools\android-sdk`——那是一个指向真实 SDK 的链接，链接没了构建就报「在类路径或引导类路径中找不到程序包 java.lang」，其实就是 `-bootclasspath` 指向了空位置。现在两个脚本共用 `../sdk.sh` 的 `pick_android_sdk`：按 `ANDROID_SDK_ROOT` → `D:\Program\Android\SDK` → 旧路径 → `D:\Projects\lsp模块\sdk` 依次探测，要求 `build-tools/36.0.0/{d8.bat,aapt2.exe,zipalign.exe,apksigner.bat}` 与 `platforms/android-36/android.jar` 齐了才算命中，本机实际命中 `D:\Program\Android\SDK`。

签名改用专用 release 密钥：`module/build.sh` 会优先读取同目录的 `signing.env`（密钥路径、alias、密码，已在 `.gitignore` 中忽略），文件缺失时才回退到 `C:\Users\hou33\.android\debug.keystore`；probe 仍走回退路径，不受影响。密钥本体是工作区 `_keys\qqsignauto-release.p12`（RSA 2048 / 30 年 / alias `qqsignauto`），**务必离线备份密钥与密码**——一旦丢失，以后所有用户都无法覆盖升级。

`D:\GradleHome\caches\{8.9,8.13,9.3.1}\transforms\*\aapt2-*-windows\aapt2.exe` 那些是 AGP 解出来的缓存副本，本流程不需要它们：缺的是整套 d8/zipalign/apksigner 加 android.jar，缓存里没有齐的。

`dist/` 里两个模块 APK 是同一份字节（25043 B），由 v1.7 源码重新构建：`classes.dex` sha256 前缀仍是 `c565d0f3`（Java 代码未改动），但 manifest 的 `package` 已换成 `io.github.zakoalice.qqsignauto`，签名也换成了上面的 release 密钥——两者叠加意味着旧版只能卸载重装。探针 `QQSignProbe.apk` 未重建，仍是原来的 debug 签名，仅供本机抓包。v1.6 那次构建与 v1.7 的差异只有 `Session` 去掉 qq.com 候选这一处；`diff -r` 与 `cmp` 都在本轮实测过。

## 发布

模块仓库托管在 `Xposed-Modules-Repo`，仓库名与包名一致：`io.github.zakoalice.qqsignauto`。

1. 改完代码同步更新 `module/build.sh` 里的 `--version-name` / `--version-code`
2. `cd module && bash build.sh`，把 `build/QQSignAuto.apk` 覆盖到 `dist/QQSignAuto.apk` 和中文名副本
3. commit / push 后，在源码仓库的 Actions 里手动运行 `Publish module APK to Xposed-Modules-Repo`，填相同的版本名与版本码
4. workflow 一次性创建带 APK 的 Release（tag 为 `<versionCode>-<versionName>`，例如 `11-1.7`），模块仓库会自动索引。注意别用「先建 Release 再补 asset」的方式，GitHub webhook 不触发，仓库不会刷新

## 已知边界

- 调度活在 QQ 进程里，不是系统闹钟：每天需要 QQ 至少被打开过（或还在后台）。21 天日志里最早的轮次是 00:00:24，最晚的是 QQ 一直没醒、醒来即补的 03:16；一整天不碰 QQ 就只能等下次开机。
- 9.2.65 的 cookie 罐路线只改了代码形状、没在真机复验——那台机器本轮没连 adb。
- 换日、20:00 的晚安卡窗口、22:00 的失败提醒都按手机自身时钟算。这次跨 10-24 与 11-12 两段会话看着像「日期跳变」，其实手机日志按天连续无缺失，时钟一直是对的，只是 PC 端时间中途同步了；只要手机日期正确，这套判断就不受影响。
- `evidence/反编译证据/` 来自 QQ 9.3.75 安装包静态反编译，类名经过 jadx 目录安全化处理（`包__名__类.java`）。

## License

GPL-3.0，见 [LICENSE](LICENSE)。
