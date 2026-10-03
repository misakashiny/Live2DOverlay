# Live2D 桌面悬浮窗 · 工作区

Android 应用：在桌面上叠加 Live2D 模型，模型内可交互、模型外触摸完全穿透。

**当前版本 `v1.7.3`**（`versionCode 11`）｜包名 `com.live2d.overlay`｜验证机型：小米平板 M2105K81AC / **Android 13**（实测）

> 🧪 **v1.7.3 新增实验能力**：接入第三方情绪引擎
> [Soullink Emotion SDK](https://github.com/nanlingyin/soullink-emotion-sdk)（VAD 连续情绪 + FACS/AU +
> 分层混音 + Idle 调度）。**默认关闭**，在界面「角色人设」卡片里开启。
> 决策与实施见 [`docs/33_Soullink引擎接入_决策与实施.md`](docs/33_Soullink引擎接入_决策与实施.md)。

> ⚠️ **已知平台限制**：全屏穿透式悬浮窗的不透明度被系统压到 **0.8**，
> 所以「整体透明度」拉到 100% 看起来仍有约 20% 透明。
> 这是 Android 12+ 的行为，**应用层无法突破**（两条修复路径均已实测证伪，见 [`docs/00_交接总纲.md`](docs/00_交接总纲.md) §五）。

---

## 从哪读起

| 你的目的 | 读这个 |
|---|---|
| **刚接手，想知道全貌** | [`docs/00_交接总纲.md`](docs/00_交接总纲.md) ★ 环境、架构、约束、验证方法 |
| **想知道还剩什么没做** | [`docs/01_迭代清单.md`](docs/01_迭代清单.md) ★ 待办按优先级排序，含落点与验收方式 |
| **想改代码，找函数** | [`docs/02_源码导读.md`](docs/02_源码导读.md) ★ 逐文件逐函数索引 + 「改 X 动哪里」速查 |
| **查历史变更 / 某个 Bug 的根因** | [`docs/10_迭代大纲（历史全记录）.md`](docs/10_迭代大纲（历史全记录）.md) 19 个 Bug 档案 |
| **只是想编译安装跑起来** | [`docs/20_工程说明.md`](docs/20_工程说明.md) |
| **★ 最新：接第三方情绪引擎了吗、怎么接的** | [`docs/33_Soullink引擎接入_决策与实施.md`](docs/33_Soullink引擎接入_决策与实施.md) |
| **自建的 AI 骨架现在是什么状态** | [`docs/32_角色动画与AI骨架_实施现状.md`](docs/32_角色动画与AI骨架_实施现状.md) |
| **AI 驱动情绪（联网 API）的原始设计稿** | [`docs/31_AI驱动情绪系统_框架推演.md`](docs/31_AI驱动情绪系统_框架推演.md) |
| **下一阶段 AI 角色系统怎么做（早期稿）** | [`docs/30_AI角色系统_架构推演.md`](docs/30_AI角色系统_架构推演.md) |

> ⚠️ **动手前必读**：交接总纲 **§三 环境事实** 与 **§五 六条不可违背的架构约束**
> （外加三条衍生规则，其中「不要假设参数名存在」是 Bug-019 换来的）。
> 它们对应的都是「改完能编译、装机才炸」的坑。

---

## 目录结构

```
.
├── README.md                    ← 本文件
├── docs/                        文档（12 份）
├── src/Live2DOverlay/           ★ Android 工程源码，可直接构建
├── artifacts/                   当前版本 APK
├── reference/
│   ├── live2d_decor.original.html   MikuCarLauncher 原始页面（改造基线，995 行）
│   ├── tools/                       自写逆向脚本（dexparse / rawzip / strdump）
│   └── soullink/                    ★ Soullink 接入的产物与脚本
│       ├── miku.soullink.profile.json   生成的模型档案（官方校验通过）
│       └── *.mjs                        生成 / 补丁 / 对照 / **参数审计** 脚本
├── evidence/                    验收证据（截图、导出日志样例）
├── _archive/                    归档区（171M，不参与构建，**可整目录删除**）
└── .workbuddy-ai/memory/        工作日历
```

---

## 快速构建

```bash
cd src/Live2DOverlay
export JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
export ANDROID_HOME='C:\Android\Sdk'
./gradlew assembleDebug --console=plain
```

> 环境变量**必须显式注入**（会话进程里读不到）；**不要加 `--offline`**；构建须**前台**执行。
> 完整装机与授权命令见 [`docs/00_交接总纲.md`](docs/00_交接总纲.md) §3.3。

---

## 接手第一件事

✅ **版本控制已建立**（原 P0-1 已完成）。

| 项 | 值 |
|---|---|
| 基线提交 | `d488d78` · `chore: 接手 v1.5.0 基线` |
| 分支 | `main` |
| 追踪 | 50 个文件 / 8.0 MB（`.git` 6.5 MB） |
| 建立日期 | 2026-10-02 |

```bash
# 若 git 不在 PATH 里，用绝对路径（本机 Git 2.55.0 装在 C:\Program Files\Git）
GIT="C:/Program Files/Git/cmd/git.exe"

"$GIT" log --oneline --stat      # 看历史
"$GIT" status                    # 看改动
"$GIT" checkout -- <文件>         # 回退单个文件（已实测字节级还原）
```

**排除项**（[`.gitignore`](.gitignore)）：`_archive/`（170 M 可再生产物）、`local.properties`（机器相关）、
`app/build/`、`.gradle/`、`.kotlin/`。

**行尾策略**（[`.gitattributes`](.gitattributes)）：`* -text`，**禁止任何行尾转换** ——
本仓库刻意混合了 LF（`live2d_decor.html`，2414 行）与 CRLF（`*.kt`），
转换会让「构建产物 MD5 比对」这类字节级校验习惯失效。

> 提交身份当前是仓库本地占位值 `DSH Agent <dsh-agent@localhost>`（本机无全局 git 身份）。
> 换成你自己的：
> ```bash
> "$GIT" config --local user.name  "你的名字"
> "$GIT" config --local user.email "你的邮箱"
> "$GIT" commit --amend --reset-author --no-edit
> ```

随后从 [`docs/01_迭代清单.md`](docs/01_迭代清单.md) §二 的 **P0 阻塞项**开始（P0-1 已划掉）。

---

## 归档区说明

`_archive/` 共 172M，全部为**可重新生成的中间产物**：

| 子目录 | 内容 | 可否删除 |
|---|---|---|
| `gradle-build-output/` | `app/build/`（30M） | ✅ `assembleDebug` 可重新生成 |
| `gradle-cache/` | `.gradle/`（3.1M） | ✅ 首次构建自动重建 |
| `apk-history/` | 历史 APK v1.0.0 ~ v1.4.0（29M） | ⚠️ 删后不可回退（当前无 git） |
| `reverse-engineering/` | 逆向解包产物，含原始 `miku.apk` / `qz.apk`（66M） | ⚠️ 结论已入档 `docs/40_*`，原始 APK 可从设备重新拉取 |
| `debug-screenshots/` | 各版本调试截图（27M） | ✅ |
| `temp/` | 临时校验脚本、根目录 `stage/`（18M） | ✅ |

**其中已抢救出的不可再生资产**（已移出归档区，勿删）：

- `reference/live2d_decor.original.html` —— MikuCarLauncher 原始页面，本项目改造基线
- `reference/tools/` —— 自写逆向脚本（`dexparse.py` / `rawzip.py` / `strdump.py`）
