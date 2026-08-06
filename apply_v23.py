#!/usr/bin/env python3
# v23: 删除充电红晕(heatOverlay)全部链路，充电只保留气泡 say

# ============ pet.html ============
p = 'app/src/main/assets/pet.html'
s = open(p, encoding='utf-8').read()

reps = [
    # 1. CSS #heatOverlay 块
    ("\n  /* Heat 热度红色渐变叠加层 */\n  #heatOverlay {\n    position: absolute;\n    left: 0; top: 0;\n    width: 100%; height: 100%;\n    background: radial-gradient(ellipse at 50% 62%, rgba(255, 45, 45, 0.78) 0%, rgba(255, 45, 45, 0.38) 55%, rgba(255, 45, 45, 0) 82%);\n    opacity: 0;\n    pointer-events: none;\n    z-index: 8;\n    transition: opacity 0.5s;\n  }\n", "\n"),
    # 2. HTML div
    ("\n  <div id=\"heatOverlay\"></div>", ""),
    # 3. DOM 引用
    ("\n  var heatOverlay = document.getElementById('heatOverlay');", ""),
    # 4. heat 变量
    ("\n  var heat = 60;", ""),
    # 5. setHeat/updateHeatOverlay/heatTimer 块
    ("\n  /* ---- Heat 热度叠加 ---- */\n  var heatTimer = null;\n  function setHeat(v) {\n    heat = Math.max(0, Math.min(100, v));\n    updateHeatOverlay();\n    if (heatTimer) { clearInterval(heatTimer); heatTimer = null; }\n    if (heat > 60) {\n      heatTimer = setInterval(function () {\n        heat -= 3;\n        if (heat <= 60) {\n          heat = 60;\n          updateHeatOverlay();\n          clearInterval(heatTimer);\n          heatTimer = null;\n        } else {\n          updateHeatOverlay();\n        }\n      }, 5000);\n    }\n  }\n  function updateHeatOverlay() {\n    heatOverlay.style.opacity = heat > 80 ? '1' : (heat > 60 ? '0.55' : '0');\n  }\n", "\n"),
    # 6. petEngine.setHeat 导出
    ("    setHeat: function (v) {\n      setHeat(v);\n    },\n", ""),
]
for old, new in reps:
    c = s.count(old)
    assert c == 1, 'pet.html count=%d: %r' % (c, old[:60])
    s = s.replace(old, new)
open(p, 'w', encoding='utf-8').write(s)
print('pet.html OK')

# ============ OverlayService.kt ============
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
    ('            Log.d("DeskPet", "checkChargingOnStart: status=$status heat=$heat")\n', '            Log.d("DeskPet", "checkChargingOnStart: status=$status")\n'),
    # 11. checkChargingOnStart 充电分支：去 setHeat 只留 say
    ('        heat = Math.min(100, heat + 8)\n                Log.d("DeskPet", "checkChargingOnStart: charging, heat now $heat")\n                val js = "window.petEngine && window.petEngine.setHeat && window.petEngine.setHeat($heat) && window.petEngine.say && window.petEngine.say(\'充电中，我陪你\')"\n', '                Log.d("DeskPet", "checkChargingOnStart: charging, say bubble")\n                val js = "window.petEngine && window.petEngine.say && window.petEngine.say(\'充电中，我陪你\')"\n'),
    # 12. POWER_CONNECTED 分支：去 setHeat 只留 say
    ('                    Log.d("DeskPet", "POWER_CONNECTED")\n                    heat = Math.min(100, heat + 8)\n                    evaluateJs("window.petEngine && window.petEngine.setHeat && window.petEngine.setHeat($heat) && window.petEngine.say && window.petEngine.say(\'充电中，我陪你\')")\n', '                    Log.d("DeskPet", "POWER_CONNECTED")\n                    evaluateJs("window.petEngine && window.petEngine.say && window.petEngine.say(\'充电中，我陪你\')")\n'),
    # 13. onIdleTick 去 heat 衰减
    ("        heat = Math.max(10, heat - 1)\n", ""),
]
for old, new in kreps:
    c = s.count(old)
    assert c >= 1, 'kt count=%d: %r' % (c, old[:60])
    s = s.replace(old, new)
open(k, 'w', encoding='utf-8').write(s)
print('OverlayService.kt OK')
