# 构建与开发说明

发布版 README 只面向用户；这份文档记录构建、签名、版本与发布流程，给维护者用。

## 环境要求

| 项 | 版本 |
| --- | --- |
| JDK | 17 |
| Android SDK | `platforms;android-34`、`build-tools;34.0.0` |
| Gradle | 8.7（AGP 8.5.2、Kotlin 1.9.24） |
| 编译目标 | compileSdk 34 / targetSdk 34 / minSdk 26 |

## 构建

```bash
export JAVA_HOME=<jdk17>
export ANDROID_HOME=<android sdk>
export GRADLE_USER_HOME=<gradle home>
./build.sh          # = testDebugUnitTest + assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。

`local.properties` 需要自己新建（不在仓库、也不在源码包里），内容一行 `sdk.dir=<你的 SDK 路径>`。

`build.sh` 会按顺序探测 `truststore.jks`（当前目录 → 工具链目录）。只有内网 HTTPS 中间人环境才需要它，正常机器探测不到就直接跳过，不影响构建。也可用 `SHIELDDNS_TRUSTSTORE` 显式指定。

## 签名

**私钥与密码都不进仓库**：

- `keystore/shielddns.jks` 和 `signing.properties` 都在 `.gitignore` 里，只在本机留存；
- 缺 `signing.properties` 时 `assembleRelease` 仍能跑，但产出的是未签名包，不可发布；
- CI 从 GitHub Secrets 解码出 keystore 并生成 `signing.properties`。

`signing.properties` 格式：

```properties
storeFile=keystore/shielddns.jks
storePassword=<storepass>
keyAlias=shielddns
keyPassword=<keypass>
```

签名证书：`CN=ShieldDNS, OU=Local, O=ShieldDNS`，SHA-256 `124517f053ac72de58d166334f7c96824522c760227413a62aa52e5dd7257562`，自签 100 年。

**换 keystore 等于换应用**：已装用户无法覆盖安装，只能卸载重装。除非确定没有存量用户，否则不要更换。

## 版本号

单一来源：根 `build.gradle` 的 `ext.appVersionCode / ext.appVersionName`，`app/build.gradle` 只引用。CI 用 `-PversionName` / `-PversionCode` 按 tag 覆盖。

规则：

- 发布版 `x.y.z`（versionCode 单调递增）；
- 测试版 `x.y.(z+1)v1`，同一轮多次启动递增 `vN`，发布时去掉 `vN` 后缀；
- `README.md` 只写最终用户说明；测试迭代与开发说明写在本文件。

## 复现验收

重建出来的 APK **sha256 不会与原包相同**（zip 条目时间戳随构建时间变），但内容是一致的。判断「同一份」看这三条：

1. `unzip -v` 的条目数与每个条目 CRC 全等（0.1.1 为 427 条）；
2. `apksigner verify --print-certs` 证书 SHA-256 一致；
3. `aapt2 dump badging` 的 `versionName` / `versionCode` / `minSdk` / `targetSdk` 一致。

快照里登记的 sha256 只标识那一个具体文件，不能当「从源码重建」的判据。

## 发布流程

1. 改根 `build.gradle` 的版本号，同步 `README.md` 版本记录；
2. 提交，打 tag `vX.Y.Z` 并推送；
3. Actions 跑单测 → 构建签名 APK → 生成 `ShieldDNS-Android-X.Y.Z.apk`、`ShieldDNS-Android-X.Y.Z-source.zip`（**不含 keystore 与 signing.properties**）、`.sha256`，挂到 Release。

## 已知环境坑

1. 构建沙箱的 HTTPS 被中间人，JDK 自带 cacerts 里没有那张 CA。已通过 `SHIELDDNS_TRUSTSTORE` + `gradle.properties` 解耦，正常机器无需配置。
2. 该沙箱 `sdkmanager` 不可用（`HTTP_PROXY`/`HTTPS_PROXY` 为空时 URL 解析失败），SDK 是手工解包安装的；换机器用标准 sdkmanager 即可。
3. Robolectric 首次运行需要下载 `android-all`，离线环境会失败，需预先热好 Gradle 缓存。

## 测试构成（20 条）

| 文件 | 条数 | 覆盖 |
| --- | --- | --- |
| `CoreTest.kt` | 8 | 规则语法、空规则、报文解析与应答构造、UDP 封装、缓存换 ID、订阅条数 |
| `PipelineTest.kt` | 8 | 端到端管线：拦截→NXDOMAIN、放行→A 记录、二次查询命中缓存且换 ID、非 53 端口忽略；TCP 段封装/解析与校验和、握手→查询→应答、未知连接回 RST、FIN 关闭 |
| `ResolverTest.kt` | 1 | 本地假上游的转发链路 |
| `UiTest.kt` | 3 | Robolectric 拉起 MainActivity 的真实测量断言 |

`ResolverTest` 的转发测试和 `PipelineTest` 的用例合计 17 条非界面测试，另外 3 条是 Robolectric；**20 是总数，不要把端到端用例重复计进去**。
