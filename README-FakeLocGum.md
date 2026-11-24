# FakeLocGum 1.17.1 — 慢跑联动版

包名：`io.github.guanhui999.fakelocgum`。详细交付说明见 [`docs/FakeLocGum-慢跑联动与改包交付-20261124.md`](docs/FakeLocGum-慢跑联动与改包交付-20261124.md)。原 README 保留为项目历史说明。

## 使用

- 这是新包名，不会覆盖旧 FakeLoc。请在 LSPosed 停用旧模块，再启用 FakeLocGum 并重新设置作用域，避免双重注入。
- 首页 → 运动与传感器 → 恢复慢跑基准：默认3m/s、160步/分钟、152–168范围、约1.12m步幅，起步6秒、松手减速2秒。
- 慢跑联动开启、手动覆盖关闭，悬浮摇杆幅度/灵敏度继续调速。
- 主停止和取消手势立即停止；正常松手逐渐减速。
- 显示的步频是模型输出，不是第三方实际接收确认；日志使用 `FakeLocGum/Steps`。
- 原包名绑定的地图Key需要重新核对厂商控制台的包名/签名限制。

## 构建

JDK17、Android SDK36 / build-tools35.0.0、NDK27.0.12077973、CMake3.22.1。AGP8.7.3/Kotlin2.2.10保持。

```cmd
gradlew.bat assembleRelease
```

当前交付机器可直接使用 `tools\verify-mcp-release.cmd`（配置了本机SDK/Gradle缓存，离线构建）。源码包包含 frida-gum.a，不含签名私钥、local.properties、Gradle缓存、build产物。外部机器首次构建需自行提供SDK和依赖缓存/网络。

## 回归

release编译后，设置JAVA_HOME、ANDROID_HOME、GRADLE_USER_HOME，安装Python3与g++：

```cmd
python tools\verify_all_jog.py
```

结果：242项行为回归、14项结构检查通过。曲线CSV在 `tools/verification/jog/jog-3mps-trace.csv`。测试边界使用Android/Binder/Gum替身；不代表小米15/Android17已真机验收，也不保证第三方不可检测。
