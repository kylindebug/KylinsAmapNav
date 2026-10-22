# 配置与安装

## 申请高德 Key

1. 登录[高德开放平台](https://console.amap.com/dev/key/app)，在“我的应用”中创建或选择应用，点击“添加 KEY”。
2. 服务平台选择 Android，PackageName 填写 `com.kylins.amapnav`。
3. 运行 `./gradlew.bat :phone:signingReport` 查看手机应用签名 SHA1，按申请表要求填入所用构建版本对应的安全码。调试安装用 debug 签名；发布构建使用自己的发布签名。不要使用他人的 SHA1。
4. 将 `amap.local.properties.example` 复制为 `amap.local.properties`，填写 `AMAP_ANDROID_KEY=你申请的Key`。该配置不提交到仓库。

## 构建

准备 JDK 17 或更高版本、Android SDK 35，设置 `JAVA_HOME`、`ANDROID_HOME`。

```powershell
./gradlew.bat :phone:assembleDebug :watch:assembleDebug
```

两个安装包应使用相同签名。Key 只进入构建后的手机安装包，请勿公开含私人 Key 的 APK。手机包含 ARM 32/64 位高德库；手表不包含高德 SDK 或定位权限。

## 安装

手机可以直接打开自己构建的手机 APK 安装。手表开启开发者选项和无线调试，与电脑连入可互通的网络，再执行：

```text
adb pair <手表IP>:<配对端口>
adb connect <手表IP>:<连接端口>
adb devices
adb -s <手机设备标识> install -r phone/build/outputs/apk/debug/phone-debug.apk
adb -s <手表设备标识> install -r watch/build/outputs/apk/debug/watch-debug.apk
```

配对端口和连接端口以手表页面为准。首次导航在手机授予精确定位权限；两端可授予通知权限。手表不需要定位权限；安装后可关闭无线调试。

升级到 0.5 时必须更新两端，手机负责导航，手表负责显示。手机需要联网并与手表保持通信。手机未获手表确认约 90 秒后停止；会话建立后手机约 60 秒未收到手表心跳、或手表约 30 秒未收到手机指引时自动停止（检查随服务调度执行）。
