# FakeLocGum
LSPosed + Frida Gum 原生插桩虚拟定位&步数注入模块，基于 Cobylinweiqi/FakeLoc 1.17.1 二次开发

## 功能说明
| 功能 | 介绍 |
| ---- | ---- |
| 场景预设 | 步行 / 驾车 / 室内静止，一次设定精度、速度与漂移三个互相牵制的参数，组合实现平滑轨迹 |
| 使用真实定位 | 以当前位置为起点（需要定位权限，受5秒节流保护） |
| 粘贴坐标 | 从剪贴板识别坐标、地图链接、geo URI，一键填入 |
| 生效范围 | **LSPosed 作用域 + App 目标包名双重分流**，只对选中的目标包生效 |
| 信号微调 | 精度 / 海拔 / 速度 / 步行漂移，设置页面默认打开；开关关闭后不再下发模拟值 |
| 假地址 | 城市 / 省 / 区 跟着假坐标走（地图SDK反查，含 adcode 与城市地名），可单独关掉 |
| 步数注入 | 支持传感器步数伪造，适配运动类APP |
| 反检测 | 抹除 `isFromMockProvider` / `isMock` / AppOps / SettingsProvider 的 mock 标记；可锁定原始定位源，解决地图跳回真实位置问题 |

## 使用方法
### 1. 安装
从 [最新 Release](https://github.com/GuanHui999/FakeLocGum/releases/tag/v1.0) 下载
`FakeLocGum-<版本>.release.apk`，或在 LSPosed 管理器的在线仓库搜索 **FakeLocGum** 直接安装。

包名：`io.github.guanhui999.fakelocgum`
> APK 使用本项目专用签名密钥，**自己编译出来的包签名不同**，两者不能互相覆盖安装；更新请始终走 Release，旧版别的包需要先卸载。

### 2. 在 LSPosed 里激活
1. 启用 **FakeLocGum** 模块
2. **配置作用域**
   - 建议勾选 `android` 和 `com.android.providers.settings`（全局反检测）
   - 再把你想要伪造定位、步数的App包名添加上去
3. 重启设备，或至少重启目标App
4. 打开 App，界面顶部不再显示「未连接到 LSPosed 服务」即为激活成功

> ⚠️ 只勾选目标App时，Google Play 服务融合定位拿到的仍是真实坐标

## 注意事项
- 本项目仅用于技术学习、本地测试研究
- 禁止用于打卡作弊、违规绕过风控等违反平台规则的场景
- HAL / Gum Hook 方案风控强度较高，不保证对所有APP永久有效

## 编译
1. Android Studio 打开项目
2. 配置 NDK，启用 Frida Gum
3. 直接 assembleRelease 打包

## 致谢
原项目：https://github.com/cobylinweiqi/FakeLoc
