<div align="center">

<img src="assets/logo.png" width="112" alt="虾大助手Plus">

# 虾大助手Plus

一个面向xmu个人学习场景的安卓客户端：签到、课表、考试、成绩与课件……

基于 [democard/xmu_assistant](https://github.com/democard/xmu_assistant) v1.8.4 二次开发，许可证沿用 Apache-2.0，原项目版权与声明保留。

</div>

## 功能

**继承自原版的基础能力**

- 全学期课表（周视图 / 日程视图）并支持 `.ics` 日历导出
- 成绩查询、GPA / 加权均分统计与长图分享、模拟计算、排名查看
- 考试安排查看、考前提醒
- 签到监控与通知（系统通知、PushPlus、QQ 邮箱）
- 课件批量下载，支持断点续传

**本项目的增强**
（v1.0版本与原版差异见`差异清单.md`）
- **隐私模式**：自定义关键词替换与顶部标题字母化，不被旁人窥探；文件名保持原样以便查找，成绩页排名可设为等长星号
- **周课表桌面小组件**：原生布局绘制、随系统深浅色即时切换、点击查看详情，考试一并显示
- **今日课程小组件**：今日课程 + 明日预告，明日区与 App 内卡片同构，考试一并显示
- **自定义考试**：自由录入非教务系统的考试安排，按xmu官方作息节次落到课表，多节次可离散选择
- **课程配色**：按学期课表邻接关系分配，相邻课程不撞色，周课表与桌面组件同色
- **调课管理**：调换周次有序配对、目标周次槽位、跨周调课与还原
- **签到增强**：消息格式异常时转为全屏提醒，交回人工确认；签到页可一键「立即进行当前签到」，不受个人策略限制
- **长按快捷方式**：自动签到开关（同时启停监控）、下载课程课件、查看课表、查看签到情况
- **课件检查**：已下载文件自动标注、扫描本地目录补标；新增课件置顶，没有文件的课程折叠收起
- **专业排名证明**：按范围向教务申请计算，从证明 PDF 解析名次并标注人数与统计口径，导出文件名带范围与时间
- **稳定性增强**：会话恢复治理、冷启动登录误报与登录争用修正、请求并发与缓存时序修复、签到重复提交去重、错误码统一映射、加密存储损坏自愈
- **独立发布签名**：不再使用公开调试签名，覆盖升级需同签名安装包

## 安装

要求 Android 8.0（API 26）及以上。

- 安装包随仓库分发：[release/虾大助手Plus-1.3.apk](release/虾大助手Plus-1.3.apk)
- 从原项目（公共调试签名）或更早的非独立签名版本升级，需先卸载，本地数据不保留
- 登录使用个人统一认证账号；签到提醒依赖前台服务，首次使用请按应用内使用指南授予通知权限并关闭电池优化

## 从源码构建

```bash
git clone https://github.com/corqna-e/xmu_assistant_plus.git
cd xmu_assistant_plus/android
./gradlew :app:assembleDebug
```

- JDK 17、Android SDK 35、Gradle Wrapper 8.7（仓库自带）
- 也可用 Android Studio 打开 `android/` 目录直接构建
- Release 签名口令通过 `android/local.properties` 注入（不进仓库），无配置时 release 构建会直接失败，debug 构建不受影响

## 项目结构

[开发文档导航](documentation/README.md) · [贡献与目录约定](CONTRIBUTING.md) · [模块定位](documentation/architecture.md)

```
xmu_assistant_plus/
├── android/                    # Kotlin + Compose 原生客户端
│   ├── app/                    # 应用模块：源码、资源、组件、构建配置
│   │   ├── src/main/java/      # 业务代码（包名 com.xmu.assistant）
│   │   ├── src/main/res/       # 界面资源、组件布局与配色
│   │   └── build.gradle.kts    # 应用构建配置与签名
│   ├── gradle/                 # 版本目录、Wrapper 与镜像配置
│   └── keystore/               # 仅含公开调试签名（发布密钥不入库）
├── assets/                     # README 品牌图
├── documentation/              # 公开维护文档：模块定位、接口兼容
├── release/                    # 随仓库分发的安装包
├── CONTRIBUTING.md             # 提交范围与目录约定
├── LICENSE                     # Apache-2.0
└── README.md
```

Android 端使用 Kotlin、Jetpack Compose、Material 3、OkHttp、WorkManager 与 AlarmManager。地图选点为自绘栅格瓦片，不依赖第三方地图 SDK；PDF 文字提取使用 PDFBox-Android。依赖版本以 [版本目录](android/gradle/libs.versions.toml) 为准，构建参数以 [应用构建配置](android/app/build.gradle.kts) 为准。

仓库只分发可运行的安卓工程与必需的 Gradle 构建文件，不含测试代码与测试数据。发布时核对 `versionName` / `versionCode`、`assembleRelease` 结果与安装包签名，确保 `release/` 内安装包与源码同一次构建产出；文件名随 `versionName` 变化。

## 数据与隐私

### 保存在本机

| 内容 | 存储方式 |
| --- | --- |
| 登录凭据、会话 Cookie、成绩与课表缓存 | `EncryptedSharedPreferences`（AES-256，密钥由系统 Keystore 保管） |
| 加密存储损坏时 | 自动重建存储并提示一次重新登录，不上报任何内容 |
| 考试缓存、课表快照、签到历史 | 应用私有目录，明文 |
| 桌面组件读取的登录态与今日摘要 | 明文偏好文件，只含布尔值与当日展示文本 |
| 调课与搬课记录、课程地点库 | 明文偏好文件，与账号无关，登出后保留 |
| 隐私模式关键词 | 明文偏好文件，属于显示偏好 |

- 全部数据留在应用私有目录，`allowBackup=false`，不参与系统备份与跨设备同步；卸载应用即清除。
- 桌面组件需要读取登录态，因此该状态在明文偏好文件中另存一份，不含账号、密码与 Cookie。

### 网络请求

- 仅访问学校教务与统一认证域名、课件平台，请求头与超时参数原样透传。
- 可选通知出口由你自行开启：PushPlus（填自己的 token）与 QQ 邮箱（填自己的发件与收件信息），关闭后不发送任何内容。
- 地图选点加载公开栅格瓦片（HTTPS），不附带账号信息。
- 应用不含统计、广告、崩溃上报等第三方 SDK，不做遥测。

### 权限用途

| 权限 | 用途 |
| --- | --- |
| 通知、全屏 Intent | 签到与考试提醒、后台监控状态 |
| 精确闹钟、开机自启 | 考试提醒与每日课表同步的准时触发 |
| 前台服务、数据同步 | 自动签到监控在后台持续运行 |
| 忽略电池优化 | 避免系统冻结监控服务，由你在使用指南中主动开启 |
| 位置 | 雷达签到与课程地点标定，仅在你主动添加地点时使用 |
| 网络状态、WiFi 状态 | 判断当前是否可联网，失败时给出可读原因 |

### 导出与分享

成绩长图、课件与证明 PDF 统一保存到「下载/xmu助手plus」，课表图片与 `.ics` 文件另行导出。它们可能包含姓名、学号、课程与成绩信息，分享前请自行检查。隐私模式只替换界面上的显示文本，不改变真实数据，也不作用于桌面小组件；课件与导出提示中的文件名保持原样。

### 使用边界

本项目是非官方的个人学习工具，请仅使用本人账号及有权访问的数据；课程安排、成绩与出勤结果以学校系统为准。

## 版本与更新

- 版本变化与对应安装包详见 [Releases](https://github.com/corqna-e/xmu_assistant_plus/releases) 。
- `release/` 内只保留当前版本的安装包。
- 自 v1.1 起不再维护 `差异清单.md`，仅供历史参考。

## 致谢与许可

- 部分记录处理与会话管理实现参考 [KrsMt-0113/XMU-Rollcall-Bot](https://github.com/KrsMt-0113/XMU-Rollcall-Bot)。
- 课件下载实现参考 [KrsMt-0113/XMUFD](https://github.com/KrsMt-0113/XMUFD)。
- 感谢 OkHttp、AndroidX、Jetpack Compose、Material 3、WorkManager、PDFBox-Android、Apache Commons Email 等开源项目。

本项目采用 [Apache-2.0 许可证](LICENSE)，源自 [democard/xmu_assistant](https://github.com/democard/xmu_assistant)（Copyright 原项目作者）。第三方依赖遵循各自许可证。
