#!/usr/bin/env python3
# v23 Kotlin 侧：删除 heat 全部链路，充电只保留气泡 say
k = 'app/src/main/java/com/sariel/deskpet/OverlayService.kt'
s = open(k, encoding='utf-8').read()
kreps = [
    # 7. heat 变量
    ("    private var heat = 60\n", ""),
    # 8. interact 函数去参去 heat
    ("    private fun interact(heatGain: Int = 5) {\n        heat = Math.min(100, heat + heatGain)\n", "    private fun interact() {\n"),
    # 9. 带参调用全改无参
    ("interact(15)", "interact()"),
    ("interact(10)", "interact()"),
    ("interact(5)", "interact()"),
    ("interact(8)", "interact()"),
    ("interact(4)", "interact()"),
    ("interact(3)", "interact()"),
    # 10. checkChargingOnStart 日志
    ('                Log.d("DeskPet", "checkChargingOnStart: status=$status heat=$heat")\n', '                Log.d("DeskPet", "checkChargingOnStart: status=$status")\n'),
    # 11. checkChargingOnStart 充电分支：去 setHeat 只留 say（interact(8) 已被第9组替换为 interact()）
    ('                interact()\n                Log.d("DeskPet", "checkChargingOnStart: charging, heat now $heat")\n                val js = "window.petEngine && window.petEngine.setHeat && window.petEngine.setHeat($heat) && window.petEngine.say && window.petEngine.say(\\'充电中，我陪你\\')"\n', '                Log.d("DeskPet", "checkChargingOnStart: charging, say bubble")\n                val js = "window.petEngine && window.petEngine.say && window.petEngine.say(\\'充电中，我陪你\\')"\n'),
    # 12. POWER_CONNECTED 分支：去 setHeat 只留 say（28空格缩进）
    ('                            interact()\n                            evaluateJs("window.petEngine && window.petEngine.setHeat && window.petEngine.setHeat($heat) && window.petEngine.say && window.petEngine.say(\\'充电中，我陪你\\')")\n', '                            evaluateJs("window.petEngine && window.petEngine.say && window.petEngine.say(\\'充电中，我陪你\\')")\n'),
    # 13. onIdleTick 去 heat 衰减
    ("        heat = Math.max(10, heat - 1)\n", ""),
]
for old, new in kreps:
    c = s.count(old)
    assert c >= 1, 'kt count=%d: %r' % (c, old[:60])
    s = s.replace(old, new)
open(k, 'w', encoding='utf-8').write(s)
