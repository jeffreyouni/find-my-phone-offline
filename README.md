# Find My Phone — offline, voice-activated

**English** · [中文](#找手机--离线语音唤醒安卓应用)

Shout **"Hey phone"** (or *"Where are you"*, *"手机在哪里"* …) and your Android phone starts a loud alarm, vibrates and blinks its flashlight — with the screen off, locked, or in the background. Everything runs on the phone: **no INTERNET permission, no cloud, no analytics, no recordings.**

Built for a Samsung Galaxy S24 Ultra (One UI, Android 14/15) but works on any Android 10+ device.

## How it works

```
AudioRecord (16 kHz, mic) ─► adaptive energy gate ─► Vosk/Kaldi decoder (closed grammar) ─► phrase + confidence check
        ▲ foreground service (type=microphone)                                               │
        │                                                                                    ▼
   notification + wake lock                      AlarmController: ALARM-stream sound · vibration · torch · full-screen UI
```

* **ListenService** – foreground service with `foregroundServiceType="microphone"`, persistent notification with *Stop listening*, automatic microphone re-open with back-off if the audio loop fails, detection of "audio silenced by system" (Android mutes background capture silently).
* **AudioListener** – reads 100 ms blocks (200 ms in battery-saver). An adaptive noise-floor gate keeps the speech decoder **idle in a quiet room**; it wakes only when sound exceeds the room noise, replays a 300 ms pre-roll so the first syllable isn't lost, and flushes the decoder when speech ends. No audio is ever stored.
* **WakeWordDetector** – Vosk decoder restricted to `["<your phrase>", "[unk]"]`. It can only output your phrase or "unknown", and the phrase must reach a minimum word-confidence set by the **Sensitivity** slider.
* **AlarmController** – plays on `USAGE_ALARM` (audible in silent/vibrate mode, same as a clock alarm; Do Not Disturb still lets alarms through by default), sets the alarm-stream volume to your chosen level and restores it afterwards, vibrates with alarm attributes, and blinks the torch via `CameraManager.setTorchMode` (no camera permission needed). Stops after the configured duration (default 30 s) or when you press *Stop*.
* **Lock-screen UI** – a full-screen-intent notification launches `AlarmActivity` (shows over the lock screen, turns the screen on) with a big Stop button.

### Engine choice (Phase 1 research)

| Engine | Verdict |
|---|---|
| **Vosk (Kaldi), Apache-2.0** | **Chosen.** Works on Android (arm64), fully offline, *arbitrary phrases* through a runtime grammar (no model training), English **and Chinese** small models (~40 MB each), no licence cost. Verified here on-host: grammar mode returns `hey phone` / `手机在哪里` correctly and returns only `[unk]` for unrelated sentences. |
| openWakeWord | Excellent fixed wake words, tiny CPU cost, but no official Android runtime (needs custom ONNX/TFLite integration), and *every* new phrase needs a trained model. No Chinese models. Good future option if the battery cost of Vosk matters. |
| PocketSphinx | Works offline with keyword spotting but is older, noticeably less accurate, high false-positive rate, no maintained Chinese setup. |
| Porcupine | Best-in-class, but proprietary + requires an access key. Excluded (not open source). |

**Honest limits**

* Custom **English** phrases work only with words in the model vocabulary (~10k words); unusual names/words are mapped to `[unk]`. Pick ordinary words. Phrases of 2+ syllables/words (≥ ~1 s of speech) are far more reliable than a single short word.
* Custom **Chinese** phrases use free decoding + text matching (the two built-in Chinese phrases use the cheaper grammar mode). Chinese support is more experimental than English.
* Detection needs ~0.5–1 s of silence/gate-close after your phrase, so expect ≈1 s latency. Distance: a normal-to-raised voice across a room works; a closed bag/pocket will not.
* Vosk is a full speech recogniser, so it uses more battery than a dedicated wake-word chip. The energy gate means that in a quiet home it does almost nothing; in a noisy room (TV, music, talking) the decoder runs and battery use rises. Turn on **Battery-saving mode** if that matters.
* You can import any other [Vosk model](https://alphacephei.com/vosk/models) (.zip) in Settings and use it with the *Imported model phrase* option.

## Requirements

* Android 10 (API 29) or newer. Target SDK 36.
* To build: JDK 17, Android SDK platform 36 (the Gradle wrapper handles Gradle itself), internet *only at build time* to fetch the models.

## Build the APK

```bash
./scripts/fetch_models.sh            # downloads the two Vosk models (~80 MB) into app/src/main/assets/models
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests: `./gradlew :app:testDebugUnitTest`

GitHub Actions builds the same debug APK on every push (`.github/workflows/build-debug-apk.yml`, artifact **FindMyPhone-debug-apk**).

## Install on the Galaxy S24 Ultra

1. Copy `app-debug.apk` to the phone (USB, Quick Share, Drive…) **or** `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Open the file and allow *Install unknown apps* for your file manager if asked (Play Protect may warn about an unknown developer — choose *Install anyway*).
3. Open **Find My Phone**, tap **Start Listening**, allow **Microphone** and **Notifications**.
4. Open **Settings** (gear) and finish the Samsung setup below.

### Samsung One UI setup (do this once — it is what makes it reliable)

1. **Settings → Apps → Find My Phone → Battery → Unrestricted.**
2. **Settings → Battery → Background usage limits**: turn off *Put unused apps to sleep*, and make sure Find My Phone is **not** in *Sleeping apps / Deep sleeping apps*; add it to *Never auto sleeping apps*.
3. **Settings → Apps → Find My Phone → Permissions → Microphone → Allow** (*Allow only while using the app* also works because the service is started while the app is open).
4. **Settings → Notifications → App notifications → Find My Phone** — allow, including **full-screen notifications** (Android 14+: the app has a button for this in Settings).
5. Pin/lock the app in the Recents screen (tap the app icon on its card → *Keep open*).
6. Don't run another app that holds the microphone (call recorders, some voice assistants) at the same time — Android gives the mic to only one "priority" recorder.
7. **After a reboot you must open the app and tap Start Listening again.** Android 14+ forbids starting a microphone foreground service from the background (and from boot), so the app deliberately does *not* auto-start.

The green microphone dot in the status bar and the notification *"Listening for 'hey phone'"* show whenever the mic is active.

## Test plan

### Automated (no phone needed)
| Check | How |
|---|---|
| Phrase matching, grammar format, sensitivity mapping | `./gradlew :app:testDebugUnitTest` |
| Project compiles, APK assembles | `./gradlew :app:assembleDebug` (also in CI) |
| Decoder accuracy of the chosen engine/grammar | scripts used during development: TTS-generated "hey phone" / "手机在哪里" accepted, unrelated sentences rejected |

### Must be done on a physical Samsung device
| # | Test | Pass criterion |
|---|---|---|
| 1 | Install, grant permissions, **Start Listening** | notification + mic dot shown; status "Listening — microphone ON" |
| 2 | Foreground detection: say "Hey phone" from ~2 m | alarm within ~1–2 s, Stop button works |
| 3 | **Screen off** (power button), say phrase | alarm + screen turns on |
| 4 | **Locked** phone, phrase | full-screen alarm over lock screen; sound audible; torch blinks; vibrates |
| 5 | **Background**: open YouTube/Chrome, say phrase | alarm still triggers |
| 6 | **Silent / vibrate / DND (alarms allowed)** | alarm still audible |
| 7 | Airplane mode + Wi-Fi/mobile data off | detection still works (fully offline) |
| 8 | Soak: leave listening 2–8 h with the phone idle on a desk | still responds at the end; check *Settings → Battery → Battery usage* for Find My Phone drain; compare with Battery-saving mode on |
| 9 | Noise: play TV/music for 10 min | no (or few) false alarms; tune Sensitivity |
| 10 | Kill/recover: swipe app from Recents | service keeps running (if not, redo the Samsung battery steps) |
| 11 | Stability: start/stop 10×, rotate, change phrase while listening | no crash, mic released on Stop (dot disappears) |
| 12 | Permission denial | message + *Open app settings* button |

Please report results of 2–5 and 8 — they are the real acceptance tests and cannot be verified without hardware.

## Privacy

No INTERNET permission is declared (`aapt dump permissions` will confirm). Audio lives only in small in-memory buffers, goes to the on-device recogniser, and is discarded. Nothing is written to disk except your settings (SharedPreferences) and, if you import one, the speech model. `allowBackup` is off.

## Project layout

```
app/src/main/java/com/gujeff/findmyphone/
  wake/     AudioListener (mic loop + gate), WakeWordDetector (Vosk), ModelManager, PhraseMatcher
  service/  ListenService (foreground microphone service), AppState
  alarm/    AlarmController (sound, vibration, torch)
  ui/       Compose screens, MainActivity, AlarmActivity, MainViewModel
  data/     Settings, WakePhrase
scripts/fetch_models.sh   downloads the Vosk models
.github/workflows/        CI debug APK
```

## Licence

[GPL-3.0-or-later](LICENSE). You may use, modify and redistribute this app (including commercially), but any distributed derivative must also be released under the GPL with its source code. Vosk and the bundled models are Apache-2.0, which is compatible with GPL-3.0.

---

# 找手机 — 离线语音唤醒安卓应用

[English](#find-my-phone--offline-voice-activated) · **中文**

对着手机喊 **"Hey phone"**（或 *"Where are you"*、*"手机在哪里"* 等），手机就会立刻响起大声警报、振动并闪烁闪光灯——锁屏、息屏、后台都可以。所有处理都在本机完成：**没有 INTERNET 权限，不联网，不上传，不录音，无统计追踪。**

为三星 Galaxy S24 Ultra（One UI，Android 14/15）优化，也适用于任何 Android 10+ 设备。

## 工作原理

```
AudioRecord（16 kHz 麦克风）─► 自适应能量门限 ─► Vosk/Kaldi 解码器（封闭语法）─► 唤醒词 + 置信度判断
        ▲ 前台服务（类型 = microphone）                                              │
        │                                                                             ▼
   常驻通知 + 唤醒锁                          AlarmController：闹钟音量流播放 · 振动 · 闪光灯 · 全屏界面
```

* **ListenService**：前台服务（`foregroundServiceType="microphone"`），带常驻通知和"停止监听"按钮；音频循环出错时自动退避重开麦克风；能检测"系统静默了后台录音"（Android 会悄悄把后台录音变成全零）并自动恢复。
* **AudioListener**：每 100 ms 读取一块音频（省电模式 200 ms）。自适应底噪门限让语音解码器在安静房间里**几乎不工作**；声音超过环境噪声才唤醒，并回放 300 ms 预缓冲以免丢掉第一个音节；说完后冲刷解码器。音频不会被保存。
* **WakeWordDetector**：Vosk 解码器被限制为 `["<你的唤醒词>", "[unk]"]`，只能输出唤醒词或"未知"，且词置信度必须达到"灵敏度"滑块设定的阈值。
* **AlarmController**：使用 `USAGE_ALARM` 播放（静音/振动模式下也能响，与系统闹钟一致；勿扰模式默认也允许闹钟），按你设置的音量设置闹钟音量流并在结束后恢复；用闹钟属性振动；通过 `CameraManager.setTorchMode` 闪烁闪光灯（无需相机权限）。到达设定时长（默认 30 秒）或点击"停止"后结束。
* **锁屏界面**：全屏通知启动 `AlarmActivity`（锁屏上显示并点亮屏幕），带一个大号"停止"按钮。

### 引擎选型（第一阶段调研）

| 引擎 | 结论 |
|---|---|
| **Vosk (Kaldi)，Apache-2.0** | **选用。** 支持 Android（arm64），完全离线，通过运行时语法支持*任意唤醒词*（无需训练模型），有英文和**中文**小模型（各约 40 MB），无授权费用。已在电脑上用真实模型验证：语法模式能正确识别 `hey phone` / `手机在哪里`，对无关句子只返回 `[unk]`。 |
| openWakeWord | 固定唤醒词效果很好、CPU 占用很小，但没有官方 Android 运行时（需自行集成 ONNX/TFLite），且每个新唤醒词都要训练模型，没有中文模型。如果将来更看重耗电，可作为备选。 |
| PocketSphinx | 可离线关键词检测，但较老、准确率明显更低、误报高，中文方案缺乏维护。 |
| Porcupine | 效果最好，但是闭源并需要 access key，不符合开源要求，已排除。 |

**如实说明的限制**

* 自定义**英文**唤醒词只能使用模型词表（约 1 万词）中的词；生僻词/人名会被识别为 `[unk]`。请使用常见单词。两个以上单词/音节（约 1 秒以上语音）的短语比单个短词可靠得多。
* 自定义**中文**唤醒词使用自由解码 + 文本匹配（两个内置中文唤醒词用更省电的语法模式）。中文支持比英文更偏实验性。
* 说完后约需 0.5–1 秒才判定，所以延迟约 1 秒。距离：正常到提高音量的声音可覆盖整个房间；手机在包里或口袋里则不行。
* Vosk 是完整的语音识别器，比专用唤醒词芯片更耗电。能量门限使安静的家中几乎不耗电；吵闹环境（电视、音乐、聊天）下解码器会持续运行、耗电上升，可开启**省电模式**。
* 可在设置中导入任何其他 [Vosk 模型](https://alphacephei.com/vosk/models)（.zip），并通过"导入模型唤醒词"选项使用。

## 环境要求

* Android 10（API 29）或更高；目标 SDK 36。
* 构建：JDK 17、Android SDK platform 36（Gradle 由 wrapper 处理）；*仅构建时*需要联网下载语音模型。

## 构建 APK

```bash
./scripts/fetch_models.sh            # 下载两个 Vosk 模型（约 80 MB）到 app/src/main/assets/models
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

运行单元测试：`./gradlew :app:testDebugUnitTest`

GitHub Actions 会在每次 push 时构建同样的 debug APK（`.github/workflows/build-debug-apk.yml`，产物名 **FindMyPhone-debug-apk**）。

## 安装到 Galaxy S24 Ultra

1. 把 `app-debug.apk` 传到手机（USB、Quick Share、网盘……），或执行 `adb install -r app/build/outputs/apk/debug/app-debug.apk`。
2. 打开文件；如提示，请为文件管理器允许"安装未知应用"（Play 保护可能提示未知开发者，选择"仍然安装"）。
3. 打开**找手机**，点击**开始监听**，允许**麦克风**和**通知**权限。
4. 进入**设置**（齿轮图标），完成下面的三星设置。

### 三星 One UI 设置（只需做一次，这是稳定运行的关键）

1. **设置 → 应用程序 → 找手机 → 电池 → 不受限制。**
2. **设置 → 电池 → 后台使用限制**：关闭"将未使用的应用程序置于休眠状态"，并确认找手机**不在**"休眠应用 / 深度休眠应用"中；把它加入"永不自动休眠的应用"。
3. **设置 → 应用程序 → 找手机 → 权限 → 麦克风 → 允许**（"仅在使用中允许"也可以，因为服务是在应用打开时启动的）。
4. **设置 → 通知 → 应用通知 → 找手机**：全部允许，包括**全屏通知**（Android 14+：应用设置页里有对应按钮）。
5. 在最近任务中锁定本应用（点卡片上的应用图标 → "保持打开"）。
6. 不要同时运行其他占用麦克风的应用（通话录音、某些语音助手）——Android 只会把麦克风交给一个"高优先级"录音者。
7. **重启手机后，必须重新打开应用并点击"开始监听"。** Android 14+ 禁止从后台（包括开机）启动麦克风前台服务，所以应用刻意*不*做开机自启。

状态栏里的绿色麦克风圆点以及通知"正在监听 'hey phone'"表示麦克风正在工作。

## 测试方案

### 自动化测试（无需手机）
| 检查项 | 方式 |
|---|---|
| 唤醒词匹配、语法格式、灵敏度映射 | `./gradlew :app:testDebugUnitTest` |
| 项目可编译、可打包 APK | `./gradlew :app:assembleDebug`（CI 中也会执行） |
| 所选引擎/语法的识别效果 | 开发期间的脚本：用 TTS 生成的 "hey phone" / "手机在哪里" 被接受，无关句子被拒绝 |

### 必须在真实三星手机上进行
| # | 测试 | 通过标准 |
|---|---|---|
| 1 | 安装、授权、**开始监听** | 显示通知和麦克风圆点；状态为"监听中 — 麦克风已开启" |
| 2 | 前台检测：在约 2 米外说 "Hey phone" | 约 1–2 秒内响铃，"停止"按钮有效 |
| 3 | **息屏**（按电源键）后说唤醒词 | 响铃并点亮屏幕 |
| 4 | **锁屏**状态说唤醒词 | 锁屏上显示全屏警报；声音可闻；闪光灯闪烁；振动 |
| 5 | **后台**：打开 YouTube/Chrome 后说唤醒词 | 仍能触发警报 |
| 6 | **静音 / 振动 / 勿扰（允许闹钟）** | 警报仍能听到 |
| 7 | 飞行模式 + 关闭 Wi-Fi/移动数据 | 检测依然有效（完全离线） |
| 8 | 长时间：手机放桌上监听 2–8 小时 | 最后仍能响应；在*设置 → 电池 → 电池使用情况*查看耗电；与省电模式对比 |
| 9 | 噪声：播放电视/音乐 10 分钟 | 没有（或极少）误报；按需调整灵敏度 |
| 10 | 杀进程/恢复：从最近任务划掉应用 | 服务仍运行（否则重做三星电池设置） |
| 11 | 稳定性：开始/停止 10 次、旋转屏幕、监听中切换唤醒词 | 不崩溃；停止后麦克风被释放（圆点消失） |
| 12 | 拒绝权限 | 显示提示和"打开应用设置"按钮 |

第 2–5 项和第 8 项是真正的验收测试，没有真机无法验证，请反馈结果。

## 隐私

未声明 INTERNET 权限（可用 `aapt dump permissions` 确认）。音频只存在于内存中的小缓冲区，送入本机识别器后即丢弃。除设置（SharedPreferences）以及你导入的语音模型外，不向磁盘写入任何内容。`allowBackup` 已关闭。

## 项目结构

```
app/src/main/java/com/gujeff/findmyphone/
  wake/     AudioListener（麦克风循环 + 门限）、WakeWordDetector（Vosk）、ModelManager、PhraseMatcher
  service/  ListenService（麦克风前台服务）、AppState
  alarm/    AlarmController（声音、振动、闪光灯）
  ui/       Compose 界面、MainActivity、AlarmActivity、MainViewModel
  data/     Settings、WakePhrase
scripts/fetch_models.sh   下载 Vosk 模型
.github/workflows/        CI 构建 debug APK
```

## 许可证

[GPL-3.0-or-later](LICENSE)：可以使用、修改和再分发（包括商用），但任何分发的衍生作品必须同样以 GPL 开源并提供源码。Vosk 及内置模型为 Apache-2.0，与 GPL-3.0 兼容。
