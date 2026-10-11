# PRTS 服务端

基于 **NeoForge 21.1.250** 的 **Minecraft 1.21.1** 服务端，可同时加载 Bukkit/Spigot 插件与 NeoForge 模组。
> English: [README.md](README.md)


## 本分支（`base/new-kernel`）

本分支以干净的上游代码为起点，开始建设**下一代 PRTS 服务端内核 —— 一个双轴调度内核**。

内核围绕两条轴展开，**所有能力默认关闭**：

- **轴 A · 等**：等待点注册表。每个等待点都要说明产出者、进展读数、超时动作和退化落点；等待上界来自实测，不作假设。
- **轴 B · 算**：按域计量份额，并维护份额表与降级阶梯。某个域开始停滞时，削减它的份额，而不是让整个 tick 一起卡住。
- **计划与提交**：逐 tick 计划、作业图与调度器，以及提交账 —— 提交顺序与计划顺序一致。
- **安全与观测**：写路径上的安全网与零生效检测，以及只读观测键，如实反映内核观测到的内容。

**默认配置与基座一致。** 每一项内核能力都默认关闭；用默认配置启动时，行为与基座完全一致。每次改动都会附上与前一次的等价核对。

沿用自基座的部分：

- 干净的上游代码，功能性改动只有 PRTS 品牌与日志着色；
- 单一固定的构建工具链，产出的字节码形态稳定（编译期 Mixin 注解处理器**特意固定**，见 `gradle/libs.versions.toml`）。

平台：**仅 NeoForge** —— 本线仅支持并验证 NeoForge。`arclight-fabric` 模块仍保留在代码树中，但不参与本轮的设计、验证与行为对齐：Fabric 支持推迟到新内核在 NeoForge 上验证通过之后。本线**不支持 Forge**。

其他分支：

- `legacy/old-framework` —— 上一代多线程框架，保留作参考与存档。

状态：`0.1.0-SNAPSHOT`，开发中，不保证稳定。内核分阶段推进，默认不启用，也不承诺任何生产稳定性。

## 环境要求

- Java 21
- NeoForge 21.1.250 服务端环境（启动器会自动选用匹配的 libraries）

## 构建

```bash
./gradlew collect          # 完整构建；产出的服务端 jar 在 bootstrap/build/libs/
```

测试：

```bash
./gradlew :arclight-common:test --offline
```

## 运行

```bash
java -Xms1G -Xmx4G -jar PRTS-neoforge-1.21.1-0.1.0-SNAPSHOT.jar -nogui
```

- Bukkit/Spigot 插件放入 `plugins/`
- NeoForge 模组放入 `mods/`

## 链接

- 仓库：https://github.com/ElainAwa/PRTS-SERVER-Multithreading
- 发布：https://github.com/ElainAwa/PRTS-SERVER-Multithreading/releases

## 许可

GPL-3.0，见 `LICENSE`；归属与第三方声明见 `NOTICE`、`THIRD-PARTY.md`、`UPSTREAM.md`。
