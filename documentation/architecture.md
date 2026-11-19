# 模块定位

本仓库只有 Android 客户端。路径均相对仓库根目录。

主源码目录为 [`android/app/src/main/java/com/xmu/assistant/`](../android/app/src/main/java/com/xmu/assistant/)。

| 职责 | 主要文件或入口 |
| --- | --- |
| 页面入口与导航 | `MainActivity.kt`、`MainScreen.kt`、`AppNavigation.kt` |
| 登录、会话和请求隔离 | `TronclassLogin.kt`、`SessionRecovery.kt`、`SessionEpoch.kt`、`RequestGate.kt` |
| 签到、人数和后台监控 | `RollcallEngine.kt`、`StudentRollcallProgress.kt`、`RollcallMonitorService.kt` |
| 课表与小组件 | `XmuScheduleClient.kt`、`SchedulePlanner.kt`、`ScheduleWidgetProvider.kt`、`ScheduleWeekWidgetProvider.kt`、`ScheduleWeekWidgetData.kt` |
| 小组件覆盖层与主题 | `WidgetDetailOverlay.kt`、`WidgetTheme.kt`、`WidgetTapPreview.kt` |
| 成绩与排名 | `XmuScoreAutoQueryClient.kt`、`ScoreShare.kt`、`XmuRankClient.kt` |
| 考试与课件 | `XmuExamClient.kt`、`ExamReminder.kt`、`CoursewareClient.kt` |
| 自定义考试 | `CustomExamStore.kt`、`CustomExamInput.kt`、`ExamScheduleMerge.kt` |
| 隐私模式 | `PrivacyMode.kt` |
| 教评助手 | `EvaluationHelperPage.kt` |
| 设置子页 | `SettingsSubPages.kt` |
| 课表地点与地图选点 | `RadarCoordinates.kt`、`TileMapPicker.kt`、`MapPicker.kt` |
| 考试提醒计划有效性 | `ExamReminderPlan.kt` |
| 下载传输与文件校验 | `FileDownloadTransport.kt`、`DownloadBodyWriter.kt` |
| 依赖和应用配置 | [版本目录](../android/gradle/libs.versions.toml)、[应用构建配置](../android/app/build.gradle.kts) |
| 单元测试和样例 | [app/src/test/](../android/app/src/test/) |

`networkBenchmark` 开发变体及其测试位于 `app/src/networkBenchmark/` 与 `app/src/testNetworkBenchmark/`，与普通发布构建分开维护。

下载传输层负责请求、重定向和全量重试，文件写入层负责编码、范围与长度检查。请求门在任务真正结束时释放；会话结果失效与实际登录在途分别管理。提醒计划使用持久化随机标识，让取消或替换后的旧广播失效；该偏好文件不保存账号和考试内容。

## 维护入口

- [scripts/](../scripts/)：安装已构建的 APK 并启动应用。
- [接口兼容说明](number-code-compatibility.md)：数字签到接口的兼容口径。
- [根 README](../README.md)：功能与安装说明。

构建命令见 [android/README.md](../android/README.md)；运行前确认命令要求的工作目录和环境。
