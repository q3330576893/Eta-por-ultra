package io.github.mangi.eta.agent.quickactions

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.ZonedDateTime

/** A strict local grammar. Uncertain commands return null to the existing conversational path. */
internal object DailyIntentRouter {
    private const val NUMBER = "[0-9零〇一二两三四五六七八九十百千万]+"
    private val unsafe = Regex("不要|别|不用|不必|无需|取消|删除|关闭|停止|撤销|如果|假如|要是|倘若|等到|然后|再(?:给|设|定|查|帮|开|提|叫)|并且|同时|顺便|之后|以前|之前|或者|还是|以及|查天气再|天气再|闹钟再|闹钟和|和.*闹钟|天气和")
    private val courtesy = Regex("^(?:请(?:你)?|麻烦(?:你)?|劳驾)?(?:帮我|给我|替我)?")
    private val timeQuestion = Regex("^(?:现在|当前|此刻)?(?:是)?(?:几点(?:钟)?(?:了)?|什么时间|时间是多少|报(?:一下|个)?时|报时间)$")
    private val dateQuestion = Regex("^(?:今天|现在|今日)(?:是)?(?:几号|几月几号|几月几日|什么日期|日期|星期几|周几|礼拜几)$|^今天(?:的)?日期(?:是什么|是多少)?$")
    private val alarmPrefix = Regex("^(?:(?:设(?:置|定)?|定|订|添加|新建)(?:一个|个)?(?:闹钟)?(?:为|在|到)?)")
    private val repeatPrefix = Regex("^(每天|每日|工作日|每个工作日|周一到周五|周一至周五|星期一到星期五|星期一至星期五|每(?:周|星期)[一二三四五六日天](?:[、和及]?[一二三四五六日天])*)")
    private val datePrefix = Regex("^(今天|明天|后天)")
    private val alarmTime = Regex("^(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|晚间)?($NUMBER)(?::($NUMBER)|(?:点|时)(半|一刻|三刻|${NUMBER}分?)?)(.*)$")
    private val alarmSuffix = Regex("^(?:的)?(?:闹钟(?:叫我起床)?|叫我起床|叫醒我|提醒我(?:起床|开会|喝水|吃药|出门|上班|下班)|叫我(?:开会|喝水|吃药|出门|上班|下班))$")
    private val timerPrefix = Regex("^(?:设(?:置|定)?|定|添加|开始|启动)(?:一个|个)?")
    private val timerBody = Regex("^(?:(半|$NUMBER)(?:个)?(?:小时|时))?(?:($NUMBER)(?:分钟|分))?(?:($NUMBER)(?:秒钟|秒))?$")
    private val halfHour = Regex("^($NUMBER)(?:个)?(?:半小时|小时半)$")
    private val cityCharacters = Regex("^[\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z .·,，\\-]{0,39}$")
    private val weatherDay = Regex("^(未来三天|最近三天|这三天|三天|今天|今日|明天|明日)")
    private val weatherWords = Regex("^(.*?)(?:的)?(?:天气(?:预报)?|气温|温度)(怎么样|怎样|如何|情况|预报|是多少|多少|几度|好吗|好不好|咋样)?$")
    private val rainWords = Regex("^(.*?)(?:会不会下雨|会下雨吗|下雨吗|有雨吗|会有雨吗|会下雨|下不下雨|下雨不|有雨没)$")
    private val locationAliases = setOf("", "这里", "这边", "当地", "本地", "我这里", "我这边", "现在", "当前")
    private val cityForbidden = Regex("[0-9]|闹钟|倒计时|提醒|叫我|设置|帮我|查|请|如果|然后|现在几点|的|和|或|与|再|吗|怎么|是否|能否|哪个|哪里|什么时候")
    private val agendaCommands = setOf("今日日程", "今天有什么安排", "查看今天日历")
    private val calculationPrefix = Regex("^(?:计算|算一下|算算)")
    private val calculationOperators = listOf("乘以" to "*", "除以" to "/", "加上" to "+", "减去" to "-",
        "取余" to "%", "加" to "+", "减" to "-", "乘" to "*", "除" to "/", "×" to "*", "÷" to "/", "−" to "-")

    // ---- 生活便利 · 本地零模型（P0）严格整串语法 ----
    // 全部使用 matchEntire 或整串集合匹配；否定/条件/复合句仍由上面的 unsafe 前置拦截并回归 null。
    private val flashlightOn = Regex("^(?:打开|开启|开|点亮|翻光)(?:一下)?(?:手电筒|手电|闪光灯)$")
    private val flashlightOff = Regex("^(?:关|关掉|关了|熄灭)(?:一下)?(?:手电筒|手电|闪光灯)$")
    private val volumePercentCommand = Regex("^(?:把)?(?:媒体|铃声|系统)?(?:音量|声音)(?:调节到|调到|调成|设置为|设为|设成|至|到|为)?($NUMBER)(?:%)?$")
    private val volumeAdjustCommand = Regex("^(?:把)?(?:媒体|铃声|系统)?(?:音量|声音)(?:调)?(大|小|高|低)(?:一点|一些|一格|一下)?$")
    private val silentCommand = Regex("^(?:把)?(?:手机|铃声|来电)?(?:调|设置|设|切)?(?:为|成|到)?静音(?:模式)?$")
    private val vibrateCommand = Regex("^(?:把)?(?:手机)?(?:调|设置|设|切)?(?:为|成|到)?(?:震动|振动)(?:模式)?$")
    private val normalCommand = Regex("^(?:把)?(?:手机)?(?:恢复|调|设置|设|切)?(?:为|成|到)?(?:响铃|铃声|正常)(?:模式)?$")
    private val openAppCommand = Regex("^(?:打开|开启|启动)(微信|淘宝|支付宝|抖音|设置|相机|照相机)(?:App|应用|客户端)?$")
    // 长动词放前面，避免"导航去西湖"被"导航"先吃掉"去"字。
    private val navigateCommand = Regex("^(?:导航去|导航到|导航至|带我去|带我到|导航)(.+)$")
    private val batteryCommands = setOf(
        "还有多少电", "还剩多少电", "还有多少电量", "还剩多少电量", "电量还有多少", "电量还剩多少",
        "电量百分比", "剩余电量", "电池电量", "电池还有多少", "电池还剩多少", "查电量", "查看电量",
    )
    private val storageCommands = setOf(
        "存储空间", "还有多少存储", "还剩多少存储", "存储空间还有多少", "存储空间还剩多少",
        "还有多少存储空间", "还剩多少存储空间", "还有多少空间", "还剩多少空间", "内部存储",
        "存储用量", "查存储", "查看存储", "存储还有多少", "存储还剩多少",
    )

    // ---- 小爱同学式生活便利 · 第二批（P0）严格整串语法 ----
    // 播放控制：整串命中，动作词与"播放"位置固定，杜绝"暂停播放然后查天气"之类复合句。
    private val mediaPlaybackCommands = mapOf(
        "暂停播放" to MediaPlaybackCommand.PAUSE, "暂停" to MediaPlaybackCommand.PAUSE,
        "继续播放" to MediaPlaybackCommand.RESUME, "继续" to MediaPlaybackCommand.RESUME,
        "播放" to MediaPlaybackCommand.RESUME,
        "下一首" to MediaPlaybackCommand.NEXT, "下一曲" to MediaPlaybackCommand.NEXT, "切歌" to MediaPlaybackCommand.NEXT,
        "上一首" to MediaPlaybackCommand.PREVIOUS, "上一曲" to MediaPlaybackCommand.PREVIOUS,
    )
    // 勿扰：unsafe 含「取消/关闭」，这几条整串必须在 unsafe 前置拦截之前放行。
    private val dndEnableCommands = setOf("开启勿扰", "打开勿扰", "开启勿扰模式", "打开勿扰模式", "进入勿扰模式")
    private val dndDisableCommands = setOf("关闭勿扰", "关掉勿扰", "关闭勿扰模式", "关掉勿扰模式", "退出勿扰模式")
    private val dndCancelCommands = setOf("取消勿扰", "取消勿扰模式")
    // 系统面板固定别名表（不做模糊匹配）。
    private val systemPanelCommands = mapOf(
        "打开WiFi设置" to SystemPanel.WIFI, "打开wifi设置" to SystemPanel.WIFI, "打开无线网设置" to SystemPanel.WIFI,
        "打开蓝牙设置" to SystemPanel.BLUETOOTH, "打开热点设置" to SystemPanel.HOTSPOT,
        "打开个人热点设置" to SystemPanel.HOTSPOT, "打开网络设置" to SystemPanel.NETWORK,
    )
    private val alarmQueryCommands = setOf("查看闹钟", "查闹钟", "看闹钟", "我定了几个闹钟", "我设了几个闹钟", "有几个闹钟", "打开闹钟")
    private val notePrefix = Regex("^(?:记一下|备忘|记个备忘|记一笔|记住)(.+)$")
    private val viewNotesCommands = setOf("查看备忘", "查看备忘录", "看备忘", "看备忘录", "查备忘", "我的备忘", "我的备忘录", "备忘列表")
    // 追算：只接整个数字串（中文/阿拉伯），运算词固定，防止吞掉额外指令。
    private val calcFollowUpCommand = Regex("^刚才那个计算再(加上|加|减去|减)($NUMBER)$")
    private val mediaPlaybackAliases = mediaPlaybackCommands.keys.toSet()
    private val panelAliases = systemPanelCommands.keys.toSet()

    fun parse(text: String, now: ZonedDateTime = ZonedDateTime.now()): DailyIntent? {
        if (text.length > 512) return null
        val normalized = normalize(text)
        if (normalized.isEmpty()) return null
        // 勿扰是唯一含 unsafe 词（取消/关闭）却属明确单一意图的整串白名单，先于 unsafe 放行。
        parseDndWhitelist(normalized)?.let { return it }
        if (unsafe.containsMatchIn(normalized)) return null
        if (normalized.any { it in "，,；;\n\r" }) return null
        val command = normalized.replaceFirst(courtesy, "")
        if (command.isEmpty()) return null
        // Keep token-separating whitespace for the calculator: "1 2" must stay invalid.
        parseCalculation(calculationCommand(text))?.let { return it }
        if (normalized.length > 120) return null
        if (timeQuestion.matches(command)) return DailyIntent.TimeNow
        if (dateQuestion.matches(command)) return DailyIntent.DateToday
        if (command in agendaCommands) return DailyIntent.TodayAgenda
        if (command.matches(Regex("^(?:设(?:置|定)?|定|订|添加|新建)(?:一个|个)?闹钟$"))) return DailyIntent.AlarmSetup
        if (command in setOf("更换城市", "更换地点", "更改城市", "更改地点", "修改天气城市", "修改天气地点", "重新选城市", "重新选地点")) {
            return DailyIntent.Weather(chooseLocation = true)
        }
        parseAlarm(command, now)?.let { return it }
        parseTimer(command)?.let { return it }
        parseLifeAction(command)?.let { return it }
        parseNewLifeAction(command)?.let { return it }
        return parseWeather(command)
    }

    /** 第二批生活便利命令：全部整串匹配；否定/条件/复合句已被 parse() 的 unsafe 前置拦截。 */
    private fun parseNewLifeAction(command: String): DailyIntent? {
        mediaPlaybackCommands[command]?.let { return DailyIntent.MediaPlaybackAction(it) }
        dndEnableCommands.firstOrNull { command == it }?.let { return DailyIntent.DndAction(DndKind.ENABLE) }
        dndDisableCommands.firstOrNull { command == it }?.let { return DailyIntent.DndAction(DndKind.DISABLE) }
        dndCancelCommands.firstOrNull { command == it }?.let { return DailyIntent.DndAction(DndKind.CANCEL) }
        systemPanelCommands[command]?.let { return DailyIntent.SystemPanelAction(it) }
        if (command in alarmQueryCommands) return DailyIntent.AlarmQuery
        if (command in viewNotesCommands) return DailyIntent.ViewNotes
        notePrefix.matchEntire(command)?.let {
            val text = it.groupValues[1].trim().trimEnd('。', '！', '!').trim()
            if (text.isEmpty() || text.length > 80) return null
            return DailyIntent.NoteAction(text)
        }
        calcFollowUpCommand.matchEntire(command)?.let {
            val operand = it.groupValues[2]
            if (ChineseDailyNumbers.parse(operand) == null) return null
            val op = if (it.groupValues[1].startsWith("加")) CalcFollowOp.ADD else CalcFollowOp.SUBTRACT
            return DailyIntent.CalcFollowUp(op, operand)
        }
        return null
    }

    /** 勿扰白名单：去掉礼貌前缀后整串比对；unsafe 里的「取消/关闭」仅对这几条放行。 */
    private fun parseDndWhitelist(normalized: String): DailyIntent? {
        val command = normalized.replaceFirst(courtesy, "")
        if (command in dndEnableCommands) return DailyIntent.DndAction(DndKind.ENABLE)
        if (command in dndDisableCommands) return DailyIntent.DndAction(DndKind.DISABLE)
        if (command in dndCancelCommands) return DailyIntent.DndAction(DndKind.CANCEL)
        return null
    }

    fun followUp(text: String, pending: DailyCommandResult?, now: ZonedDateTime = ZonedDateTime.now()): DailyIntent? {
        parse(text, now)?.let { return it }
        val command = normalize(text)
        if (command.isEmpty() || command.length > 120 || unsafe.containsMatchIn(command) || command.any { it in "，,；;\\n\\r" }) return null
        return when (pending) {
            is DailyCommandResult.NeedsAlarmTime -> parseAlarm("设置" + command.replaceFirst(courtesy, ""), now)
            is DailyCommandResult.NeedsCity -> text.trim().takeIf { it.length in 2..40 && cityCharacters.matches(it) && !cityForbidden.containsMatchIn(it) }
                ?.let { pending.request.copy(cityQuery = it, chooseLocation = true) }
            else -> null
        }
    }

    /**
     * 生活便利命令：每条都要求整串命中，绝不截取子串；参数来自 matchEntire 的完整捕获组。
     * 否定/条件句在 parse() 的 unsafe 前置检查中已被拦掉，这里只处理明确、单一意图的肯定句。
     */
    private fun parseLifeAction(command: String): DailyIntent? {
        if (flashlightOn.matches(command)) return DailyIntent.FlashlightAction(true)
        if (flashlightOff.matches(command)) return DailyIntent.FlashlightAction(false)

        volumePercentCommand.matchEntire(command)?.let {
            val percent = ChineseDailyNumbers.parse(it.groupValues[1]) ?: return null
            if (percent !in 0..100) return null
            return DailyIntent.SetVolumeAction(percent)
        }
        volumeAdjustCommand.matchEntire(command)?.let {
            return DailyIntent.AdjustVolumeAction(it.groupValues[1] in setOf("大", "高"))
        }

        if (silentCommand.matchEntire(command) != null) return DailyIntent.SetRingerAction(RingerKind.SILENT)
        if (vibrateCommand.matchEntire(command) != null) return DailyIntent.SetRingerAction(RingerKind.VIBRATE)
        if (normalCommand.matchEntire(command) != null) return DailyIntent.SetRingerAction(RingerKind.NORMAL)

        openAppCommand.matchEntire(command)?.let { return DailyIntent.OpenAppAction(it.groupValues[1]) }

        navigateCommand.matchEntire(command)?.let {
            val destination = it.groupValues[1].trim().trim('的').trim()
            if (destination.isEmpty() || destination.length > 120) return null
            // 目的地不能再次吞掉一条独立命令（例如"导航去西湖然后打开微信"应走会话路径）。
            if (unsafe.containsMatchIn(destination) || parseLifeActionWouldMatchCommand(destination)) return null
            return DailyIntent.NavigateAction(destination)
        }

        if (command in batteryCommands) return DailyIntent.BatteryStatus
        if (command in storageCommands) return DailyIntent.StorageStatus
        return null
    }

    /** 目的地过长/嵌套命令时保守拒绝，避免把复合句当作单条导航。 */
    private fun parseLifeActionWouldMatchCommand(destination: String): Boolean =
        flashlightOn.matches(destination) || flashlightOff.matches(destination) ||
            silentCommand.matches(destination) || vibrateCommand.matches(destination) ||
            normalCommand.matches(destination) || destination in batteryCommands || destination in storageCommands ||
            destination in mediaPlaybackAliases || destination in panelAliases ||
            destination in alarmQueryCommands || destination in viewNotesCommands

    private fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .trim().replace(Regex("[\\t ]+"), "")
        .trimEnd('。', '！', '!', '？', '?', '啊', '呀', '呢')

    private fun calculationCommand(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .trim().trimEnd('。', '！', '!', '？', '?').trim()
        .replaceFirst(courtesy, "").trim()

    private fun parseCalculation(command: String): DailyIntent.Calculate? {
        val prefix = calculationPrefix.find(command)
        val question = command.endsWith("等于多少")
        if (prefix == null && !question) return null
        var expression = if (prefix != null) command.substring(prefix.value.length).trim() else command
        if (question) expression = expression.removeSuffix("等于多少").trim()
        calculationOperators.forEach { (word, operator) -> expression = expression.replace(word, operator) }
        if (expression.isBlank() || !SimpleCalculator.hasExpressionAlphabet(expression)) return null
        // Syntax and resource errors belong to the typed Failure path rather than a model call.
        return DailyIntent.Calculate(expression)
    }

    private fun parseAlarm(command: String, now: ZonedDateTime): DailyIntent.SetAlarm? {
        var rest = command
        val action = alarmPrefix.find(rest)
        if (action != null) rest = rest.substring(action.value.length)
        var repeatDays = emptySet<DayOfWeek>()
        val repeat = repeatPrefix.find(rest)
        if (repeat != null) {
            repeatDays = when (repeat.value) {
                "每天", "每日" -> DayOfWeek.entries.toSet()
                "工作日", "每个工作日", "周一到周五", "周一至周五", "星期一到星期五", "星期一至星期五" ->
                    setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                else -> repeat.value.removePrefix("每星期").removePrefix("每周")
                    .mapNotNull(::weekday).toSet()
            }
            rest = rest.substring(repeat.value.length)
        }
        val day = datePrefix.find(rest)
        if (day != null) rest = rest.substring(day.value.length)
        if (day != null && repeatDays.isNotEmpty()) return null
        val match = alarmTime.matchEntire(rest) ?: return null
        val period = match.groupValues[1]
        val rawHour = ChineseDailyNumbers.parse(match.groupValues[2]) ?: return null
        val minuteText = match.groupValues[3].ifEmpty { match.groupValues[4] }
        val minute = when (minuteText) {
            "" -> 0
            "半" -> 30
            "一刻" -> 15
            "三刻" -> 45
            else -> ChineseDailyNumbers.parse(minuteText.removeSuffix("分")) ?: return null
        }
        val suffix = match.groupValues[5]
        if (!alarmSuffix.matches(suffix) && !(suffix.isEmpty() && action != null)) return null
        if (minute !in 0..59 || rawHour !in 0..23) return null
        val hour = periodHour(rawHour, period) ?: return null
        val targetDate = day?.let { now.toLocalDate().plusDays(when (it.value) { "明天" -> 1L; "后天" -> 2L; else -> 0L }) }
        if (targetDate == now.toLocalDate() && hour * 60 + minute <= now.hour * 60 + now.minute) return null
        val label = when {
            suffix.contains("起床") || suffix.contains("叫醒") -> "起床"
            suffix.startsWith("提醒我") -> suffix.removePrefix("提醒我")
            suffix.startsWith("叫我") -> suffix.removePrefix("叫我")
            else -> "闹钟"
        }
        return DailyIntent.SetAlarm(hour, minute, label, repeatDays, targetDate)
    }

    private fun periodHour(hour: Int, period: String): Int? = when (period) {
        "" -> hour
        "凌晨" -> when (hour) { in 0..5 -> hour; 12 -> 0; else -> null }
        "早上", "早晨" -> hour.takeIf { it in 1..11 }
        "上午" -> when (hour) { in 0..11 -> hour; 12 -> 0; else -> null }
        "中午" -> when (hour) { 11, 12 -> hour; 1, 2 -> hour + 12; else -> null }
        "下午" -> when (hour) { in 1..11 -> hour + 12; 12 -> 12; else -> null }
        "傍晚" -> when (hour) { in 5..8 -> hour + 12; else -> null }
        "晚上", "晚间" -> when (hour) { in 6..11 -> hour + 12; 0, 12 -> 0; else -> null }
        else -> null
    }

    private fun parseTimer(command: String): DailyIntent.SetTimer? {
        var rest = command.replaceFirst(timerPrefix, "")
        val hasPrefix = rest != command
        val leading = listOf("倒计时", "计时器", "计时").firstOrNull { rest.startsWith(it) }
        if (leading != null) rest = rest.removePrefix(leading)
        val trailing = listOf("的倒计时", "的计时器", "倒计时", "计时器", "计时").firstOrNull { rest.endsWith(it) }
        if (trailing != null) rest = rest.removeSuffix(trailing)
        if (leading == null && trailing == null) return null
        if (rest.isEmpty() || (leading != null && trailing != null)) return null
        val half = halfHour.matchEntire(rest)
        val seconds: Long = if (half != null) {
            val hours = ChineseDailyNumbers.parse(half.groupValues[1]) ?: return null
            hours.toLong() * 3600L + 1800L
        } else {
            val parts = timerBody.matchEntire(rest) ?: return null
            if (parts.groupValues.drop(1).all(String::isEmpty)) return null
            val hours = if (parts.groupValues[1] == "半") 0 else parts.groupValues[1]
                .takeIf(String::isNotEmpty)?.let { ChineseDailyNumbers.parse(it) ?: return null } ?: 0
            val minutes = parts.groupValues[2].takeIf(String::isNotEmpty)?.let { ChineseDailyNumbers.parse(it) ?: return null } ?: 0
            val remainder = parts.groupValues[3].takeIf(String::isNotEmpty)?.let { ChineseDailyNumbers.parse(it) ?: return null } ?: 0
            hours.toLong() * 3600L + minutes.toLong() * 60L + remainder + if (parts.groupValues[1] == "半") 1800L else 0L
        }
        if (seconds !in 1L..86_400L) return null
        return DailyIntent.SetTimer(seconds.toInt())
    }

    private fun parseWeather(command: String): DailyIntent.Weather? {
        var rest = command.replaceFirst(Regex("^(?:查询|查看|查|看看|看)(?:一下)?"), "")
        var period = WeatherPeriod.TODAY
        var explicitPeriod = false
        weatherDay.find(rest)?.let {
            period = weatherPeriod(it.value)
            explicitPeriod = true
            rest = rest.substring(it.value.length)
        }
        val match = weatherWords.matchEntire(rest) ?: rainWords.matchEntire(rest) ?: return null
        val rain = rainWords.matches(rest)
        var city = match.groupValues[1].removeSuffix("的")
        val suffixPeriod = listOf("未来三天", "最近三天", "这三天", "三天", "今天", "今日", "明天", "明日")
            .firstOrNull(city::endsWith)
        if (suffixPeriod != null) {
            val nextPeriod = weatherPeriod(suffixPeriod)
            if (explicitPeriod && period != nextPeriod) return null
            period = nextPeriod
            city = city.removeSuffix(suffixPeriod).removeSuffix("的")
        }
        city = city.removePrefix("在")
        if (city in locationAliases) city = ""
        if (city.isNotEmpty() && (!cityCharacters.matches(city) || cityForbidden.containsMatchIn(city))) return null
        return DailyIntent.Weather(city.ifBlank { null }, period, rain)
    }

    private fun weatherPeriod(raw: String): WeatherPeriod = when (raw) {
        "明天", "明日" -> WeatherPeriod.TOMORROW
        "未来三天", "最近三天", "这三天", "三天" -> WeatherPeriod.THREE_DAYS
        else -> WeatherPeriod.TODAY
    }

    private fun weekday(char: Char): DayOfWeek? = when (char) {
        '一' -> DayOfWeek.MONDAY; '二' -> DayOfWeek.TUESDAY; '三' -> DayOfWeek.WEDNESDAY
        '四' -> DayOfWeek.THURSDAY; '五' -> DayOfWeek.FRIDAY; '六' -> DayOfWeek.SATURDAY
        '日', '天' -> DayOfWeek.SUNDAY; else -> null
    }
}

internal object ChineseDailyNumbers {
    private val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
        '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    private val units = mapOf('十' to 10, '百' to 100, '千' to 1000)

    fun parse(raw: String): Int? {
        if (raw.isEmpty() || raw.length > 12) return null
        if (raw.all(Char::isDigit)) return raw.toIntOrNull()?.takeIf { it >= 0 }
        if (raw.any { it.isDigit() }) return null
        if (raw.all { it in digits }) return raw.map { digits.getValue(it) }.joinToString("").toIntOrNull()
        val tenThousands = raw.split('万')
        if (tenThousands.size > 2) return null
        if (tenThousands.size == 2) {
            val high = small(tenThousands[0]) ?: return null
            val low = if (tenThousands[1].isBlank()) 0 else small(tenThousands[1]) ?: return null
            return (high.toLong() * 10000 + low).takeIf { it <= Int.MAX_VALUE }?.toInt()
        }
        return small(raw)
    }

    private fun small(raw: String): Int? {
        var sum = 0
        var digit: Int? = null
        var lastUnit = 10000
        for (char in raw) {
            val value = digits[char]
            if (value != null) {
                if (value == 0) { if (digit != null && digit != 0) return null; digit = 0 }
                else { if (digit != null && digit != 0) return null; digit = value }
                continue
            }
            val unit = units[char] ?: return null
            if (unit >= lastUnit || digit == 0) return null
            sum += (digit ?: if (unit == 10 && sum == 0) 1 else return null) * unit
            digit = null
            lastUnit = unit
        }
        return sum + (digit ?: 0)
    }
}
