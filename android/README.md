# 虾大助手 Android

虾大助手的 Android 原生客户端，使用 Kotlin 与 Jetpack Compose，覆盖课表、签到监控、成绩与课件等日常场景。登录态与业务缓存保存在设备本地。

当前版本 **1.3（versionCode 208）**，支持 **Android 8.0（API 26）及以上**，compileSdk / targetSdk 为 35。包名 `com.xmu.assistant.xia`，入口 `com.xmu.assistant.MainActivity`。

[下载 APK](../release/虾大助手Plus-1.3.apk) · [项目首页](../README.md) · [开发文档](../documentation/README.md) · [贡献指南](../CONTRIBUTING.md)

## 使用入口

底部导航为「首页 / 课表 / 签到 / 更多」。课程课件、考试安排、成绩、通知设置与使用指南集中在「更多」。

| 入口 | 可以做什么 |
| --- | --- |
| 首页 | 登录账号、查看当前签到摘要、开启自动签到与签到监控 |
| 课表 | 周课表与日程两种视图、调课与还原、导出日历 |
| 签到 | 查看近期签到记录与本人状态、立即进行当前签到 |
| 更多 | 课程课件、考试安排、成绩与专业排名、设置、外观与通知、使用指南 |

应用支持浅色、深色和跟随系统主题。快捷设置磁贴可启停监控，长按应用图标可直达自动签到、课件、课表与签到。

## 功能边界

- **记录与提醒**：需要有效登录态、网络与后台运行条件；相关动作按个人策略启用，状态以原平台记录为准。
- **课表**：七天十一节的周网格与日程列表，时间冲突的课程分别展示；课表菜单可导出 `.ics`，按单双周与分段周次编排；「调课」只改本机显示，可随时还原。
- **桌面小组件**：今日课程小卡片与周课表小部件，随课表数据刷新；渲染依赖桌面启动器，刷新频率受系统限制。
- **专业排名**：在成绩页进入，按范围向教务提交计算申请，由教务统一计算后取回证明并解析名次。结果标注名次、人数与统计口径；证明 PDF 导出到「下载/xmu助手plus」，文件名带范围与导出时间。已有证明直接读取复用，成绩变化后重新提交计算才会更新。
- **考试提醒**：精确闹钟与全屏提醒受系统权限限制，请按内置「使用指南」配置；系统限制后台时不能保证准点触发。
- **课件下载**：批量下载，统一保存到「下载/xmu助手plus」，无需存储授权；平台未提供下载地址的条目只保留入口。「检查本地」扫描本机文件补标为已下载，标记为已下载却扫不到文件的显示「？已下载」。
- **隐私模式**：页面文字按规则替换，标题逐字字母化；课件与导出提示中的文件名保持原样以便查找，成绩页排名数字显示为等长星号，点击可临时查看。

## 数据与更新

账号、Cookie 等凭据使用 EncryptedSharedPreferences 保存。业务缓存与课件不属于加密凭据；小组件摘要包含课程名、时间与地点，虽不含密码或 Cookie，仍可能涉及个人安排。历史展示缓存登出时删除；通知与应答去重存储保留，并按账号 Cookie 指纹隔离。

课程雷达地点与本地课表修正是按课程匹配的持久设置，跨账号保留，登出不删除。共用设备或切换账号后，请先在对应管理页检查设置。

查询及用户启用的操作会连接相应服务；开启 PushPlus 或邮件通知会向所选服务发送通知。成绩长图、课表日历与证明 PDF 可能包含个人信息，分享前请检查。

**当前 release APK 使用仓库维护者自行保管的独立签名密钥**，密钥与口令不入库。跨签名安装会被系统拒绝（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），需先卸载；卸载会清除应用私有数据。请从本仓库 `release/` 目录下载并核对校验值。

## 构建

需要 JDK 17、Android SDK Platform 35 及对应构建工具。用 Android Studio 打开本目录并配置本机 SDK，也可以通过 `JAVA_HOME`、`ANDROID_HOME` 指定；`local.properties` 只供本机使用，不应提交。

仓库携带 Gradle 8.7 Wrapper，从 `android/` 目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

| 产物 | 相对 `android/` 的位置 |
| --- | --- |
| Release APK | `app/build/outputs/apk/release/app-release.apk` |
| Lint 报告 | `app/build/reports/lint-results-debug.html` |

仓库只包含可运行的工程，不含测试源集与基准脚本；持续集成执行 `assembleDebug` 与 `lintDebug`。`gradle/init.gradle` 是可选镜像配置，可用 `-I gradle/init.gradle` 显式加载。

### 安装到设备

在 `android/` 目录、设备已连接且 `adb` 可用时：

```powershell
adb install -r .\app\build\outputs\apk\release\app-release.apk
adb shell am start -n com.xmu.assistant.xia/com.xmu.assistant.MainActivity
```

## 源码结构

`app/src/main/java/com/xmu/assistant/` 下按职责分文件：`*Client.kt` 负责接口请求与解析，`*SectionState.kt` 承载页面状态，`*Page.kt` / `Pages.kt` / `UiComponents.kt` 为界面，`ScheduleWidget*` 为桌面小组件。模块定位见 [架构说明](../documentation/architecture.md)。
