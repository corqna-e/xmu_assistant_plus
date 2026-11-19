# 开发文档导航

[项目首页](../README.md)面向安装和日常使用；此目录面向源码维护。

| 需要做什么 | 入口 |
| --- | --- |
| 构建和安装 Android 客户端 | [Android 开发说明](../android/README.md) |
| 找到功能对应的源码 | [模块定位](architecture.md) |
| 排查记录查询的兼容问题 | [接口兼容说明](number-code-compatibility.md) |
| 了解提交范围与目录约定 | [贡献指南](../CONTRIBUTING.md) |

## 文档放在哪里

- `documentation/`：经过筛选、可随源码公开的维护说明。

已有 `.gitignore` 将 `docs/` 和 `android/docs/` 留作不提交的内部资料目录；公开文档使用 `documentation/`，避免把旧的本地内部资料一起加入版本管理。

安装包放在 `release/` 随仓库分发。根目录 `assets/logo.png` 为 README 使用的品牌图。
