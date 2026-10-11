# PRTS 服务端

基于 **NeoForge 21.1.250** 的 **Minecraft 1.21.1** 服务端，同时支持 Bukkit/Spigot 插件与 NeoForge 模组。
> English: [README.md](README.md)


## 本分支说明（`base/new-kernel`）

本分支在干净的上游基座之上承载**下一代 PRTS 服务端内核 —— 一个双轴调度内核**。

内核围绕两条轴构建，且**每一部分默认关闭**：

- **等（轴 A）** —— 等待点注册表：每个等待点声明自己的产出者、进展读数、超时动作与退化落点；等待上界**实测**而非假设。
- **算（轴 B）** —— 按域份额计量、份额表与降级阶梯：开始停滞的域被**削减**，而不是把整刻拖垮。
- **计划与提交** —— 逐刻计划、作业图与调度器，以及**提交序 = 计划序**的提交账。
- **安全与观测** —— 写路径上的安全网与零生效检测器，以及只读观测键（报告内核实际看到什么）。

**默认行为不变。** 每项内核能力都在一个**默认关闭**的开关之后；在默认配置下服务端行为与它所基于的基座一致，且每次改动都带**与上一版等价的核对**。

本分支从基座保留的部分：

- **干净的上游基座**，功能性改动只有 PRTS 品牌与日志/横幅着色；
- **单一固定构建工具链**，保证产出的字节码形态稳定（编译期 Mixin 注解处理器是**有意 pin 死**的，见 `gradle/libs.versions.toml`）。

平台：**仅 NeoForge** —— 本线只支持并验证 NeoForge。`arclight-fabric` 模块保留在代码树中，但不属于本轮的设计、验证与行为对齐范围：Fabric 支持推迟到新内核在 NeoForge 上跑通之后再评估。本线**不支持 Forge**。

其它分支：

- `legacy/old-framework` —— 上一代多线程框架线，保留作参考与历史。

状态：`0.1.0-SNAPSHOT`，开发中，不保证稳定。内核**分阶段**开发；**默认不启用**，也不对其作任何生产稳定性承诺。

## 环境要求

- Java 21
- NeoForge 21.1.250 服务端环境（启动器会使用与之匹配的 libraries）

## 构建

```bash
./gradlew collect          # 完整构建，服务端 jar 产物在 bootstrap/build/libs/
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
