## 改动说明

<!-- 这个 PR 解决什么问题？关联 Issue： -->

## 改动范围

- [ ] Web UI（`neu-lite/web` + `neu-lite-android/assets`）
- [ ] Android（`neu-lite-android`）
- [ ] Python（`neu-lite/neulite`）
- [ ] 文档
- [ ] 构建 / CI
- [ ] 其他

## 验证

<!-- 列出实际执行过的命令或页面操作 -->

```text
cd neu-lite-android
pwsh -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

或：

```text
cd neu-lite
python run.py selftest
```

## 检查

- [ ] 没有提交密码、TGT、Cookie、token、keystore、`state.json`
- [ ] 没有提交 APK / exe / 构建缓存
- [ ] 修改 Web UI 时同步了 `neu-lite/web` 与 `neu-lite-android/assets`
- [ ] 变更聚焦，没有无关格式化
- [ ] 涉及界面改动时附了打码后的截图
