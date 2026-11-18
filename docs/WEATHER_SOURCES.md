# 天气数据源与路由（3.5.4）

> 记录 2026-11-18 实测结论与代码路由，避免后人再把「IP 定位」当成「当前位置」，或把经纬度发给不支持它的接口。

## 1. 数据源与职责

| 数据源 | 用途 | 是否接收坐标 | 备注 |
|---|---|---|---|
| UAPI `https://uapis.cn/api/v1/misc/weather` | 国内地名/行政区预报，多日预报 | **否** | 一次请求同时返回 `province/city/district/adcode` 与 `forecast[]`；`forecast=true` 才有每日数据 |
| Open-Meteo `api.open-meteo.com/v1/forecast` | 真实坐标预报 | 是 | 当前位置、手动坐标、境外地点 |
| Open-Meteo Geocoding | 市级以上地名检索 | — | 中文县/镇覆盖不全 |
| Photon `photon.komoot.io/api/` | 乡镇级地名补全 | — | 只在用户显式查询时请求，串行 1.2 秒间隔 |

**UAPI 的 `lat`/`lng` 参数不存在。** 官方文档只列 `city`、`adcode`、`lang` 与若干 Pro 开关；实测传 `lat/lng` 会返回 200，但内容是 IP 所在城市（例如查池江镇坐标返回河南郑州 `410100`）。因此坐标场景一律不走 UAPI。

## 2. 路由

`ComposedWeatherService` 是唯一入口：

1. `searchCities` → 先 UAPI（中文区县直达），无结果或异常再回退 Open-Meteo/Photon。
2. `forecast` → 能确定行政区（有 `adcode`，或国内且不是「当前位置」「手动坐标」）走 UAPI；否则要求真实坐标走 Open-Meteo。
3. UAPI 失败且没有坐标时直接报错；有坐标时降级为坐标预报，不静默换成别的城市。

`QuickActionsExecutor` 的「当前位置」路径：`DeviceLocationProvider.current` 取新鲜坐标 → `WeatherLocationResolver` 用系统 `Geocoder` 解析行政区（1.5 秒上限）→ 有行政区走 UAPI，解析不出就带坐标走 Open-Meteo。**任何情况下都不会读出口 IP。**

## 3. 延迟与缓存

- 实测（2026-11-18，本机）：UAPI 地名查询 94–290 ms，`adcode` 预报 ~96 ms；Open-Meteo/Photon 同期 1.4 s–超时。
- UAPI 客户端：连接 2 s / 读 3 s / 整调用 4 s，不跟随重定向；响应上限 256 KiB。
- 内存缓存：按 `adcode` 与 `city:<name>` 两个键，TTL 10 分钟且要求缓存里含今天；命中即返回，不再联网。
- 命中限流（429）时按 `Retry-After` 记录冷却，冷却期内直接返回可读文案，不反复压测。

## 4. 播报

`WeatherBriefing.speak` 生成 `DailyCommandResult.summary`，语音播报直接读它：

- 今天/明天：`今天大余县小雨，气温12到24度。出门建议带伞。`
- 三天：按天并列，不给建议。
- 未知天气词映射为 `-1`，既不说晴也不说雨；缺温度就省略；降水概率只在接口真的返回时才念。
- 缓存过期时前面加一句「这是上次更新的预报。」

## 5. 已知边界

- 乡镇查询可能返回所属区县天气，界面上用「预报覆盖」标注实际区域，不用市级中心顶替。
- UAPI 免费额度有限，代码只对明确可重试的网络类错误重试一次。
- 真机定位精度、系统地名服务可用性、以及各 ROM 的后台限制仍需设备实测。
