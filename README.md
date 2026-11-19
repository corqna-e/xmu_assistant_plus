<div align="center">

<img src="assets/logo.png" width="112" alt="虾大助手Plus">

# 虾大助手Plus

一个面向xmu个人学习场景的安卓客户端：课表、成绩、考试、签到与课件，集中在一个应用里。

基于 [democard/xmu_assistant](https://github.com/democard/xmu_assistant) v1.8.4 二次开发，许可证沿用 Apache-2.0，原项目版权与声明保留。

</div>

## 功能

**继承自原版的基础能力**

- 全学期课表（周视图 / 日程视图）与 `.ics` 日历导出
- 成绩查询、GPA / 加权均分统计与长图分享
- 考试安排查看与考前提醒
- 签到监控与通知（系统通知、PushPlus、QQ 邮箱）
- 课件浏览与批量下载，支持断点续传
- 登录态保存与恢复，凭据经 EncryptedSharedPreferences 加密存储
- 快捷设置磁贴与应用图标长按入口

**本版本的增强**

- **隐私模式**：自定义关键词替换与顶部标题字母化，防旁人误读；对桌面小组件外几乎全部界面生效
- **周课表桌面小组件**：原生布局绘制、随系统深浅色、点击课程弹出详情覆盖层
- **今日课程小组件**：今日课程 + 明日预告，按桌面余量自适应行数
- **自定义考试**：自由录入非教务系统的考试，按xmu官方作息节次落到课表，多节次可离散选择
- **调课管理**：调换周次有序配对、目标周次槽位、跨周搬课与还原
- **成绩模拟**：模拟成绩实时对比「当前 → 计入后」的平均分与加权分
- **稳定性增强**：会话恢复治理、错误码统一映射、诊断记录导出、加密存储损坏自愈
- **独立发布签名**：不再使用公开调试签名，覆盖升级需同签名安装包

相对上游的完整差异见 [差异清单](差异清单.md)。

## 安装

要求 Android 8.0（API 26）及以上。

- 安装包随仓库分发：[release/虾大助手Plus-1.0.apk](release/虾大助手Plus-1.0.apk)
- 从旧版（原项目调试签名）升级需先卸载原应用，本地数据不保留。
- 登录使用个人统一认证账号；签到提醒依赖前台服务，首次使用请按应用内使用指南授予通知权限并关闭电池优化。

## 从源码构建

```bash
git clone https://github.com/corqna-e/xmu_assistant_plus.git
cd xmu_assistant_plus/android
./gradlew :app:assembleDebug
```

- JDK 17、Android SDK 35、Gradle Wrapper 8.7（仓库自带）
- 也可用 Android Studio 打开 `android/` 目录直接构建
- Release 签名口令通过 `android/local.properties` 注入（不进仓库），无配置时 release 构建会失败，debug 构建不受影响

技术栈：Kotlin 1.9、Jetpack Compose、Material 3、OkHttp、WorkManager、AlarmManager。

## 数据与隐私

所有数据保存在本机：凭据加密存储，课表、成绩、考试与缓存仅用于本地展示。启用 PushPlus 或邮件通知时，通知内容会发送到所选服务。导出的课表图片与证明文件可能包含个人信息，分享前请自行检查。

本项目是非官方的个人学习工具，请仅使用本人账号及有权访问的数据；课程安排、成绩与出勤结果以学校系统为准。

## 许可

本项目基于 [Apache-2.0](LICENSE) 许可证发布，源自 [democard/xmu_assistant](https://github.com/democard/xmu_assistant)（Copyright 原项目作者）。第三方依赖遵循各自许可证。
