package com.example.novelstudio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/** 一条结构化记录（人物 / 关系 / 大纲共用）。 */
typealias Rec = LinkedHashMap<String, String>

/** 全局共享状态：当前项目、素材内存缓存、忙碌/状态栏。 */
object Store {
    var rootDir: File? = null                 // projects 根目录
    var projectDir: File? = null              // 当前项目目录
    var cfg: ApiConfig = ApiConfig()
    var chars: MutableList<Rec> = mutableListOf()
    var rels: MutableList<Rec> = mutableListOf()
    var outline: MutableList<Rec> = mutableListOf()

    private val _status = MutableStateFlow("就绪。请先在「1.设置与项目」新建或打开项目。")
    val status: StateFlow<String> get() = _status
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> get() = _busy
    private val _activeTab = MutableStateFlow(0)
    val activeTab: StateFlow<Int> get() = _activeTab
    private val _summarySaved = MutableStateFlow<Int?>(null)
    val summarySaved: StateFlow<Int?> get() = _summarySaved

    fun setStatus(t: String) { _status.value = t }
    fun setBusy(b: Boolean) { _busy.value = b }
    fun isBusy(): Boolean = _busy.value
    fun setActiveTab(i: Int) { _activeTab.value = i }
    fun notifySummarySaved(ch: Int) { _summarySaved.value = ch }
}

/** 项目存储、文本解析与素材上下文构建（自桌面版 novel_writer_studio.py 移植）。 */
object Repo {

    val CHAR_FIELDS = arrayOf(
        "姓名", "性别", "年龄", "职务/身份", "身高体型", "样貌",
        "穿着特点", "性格特点", "语言特点/口头禅", "典型台词",
        "动机与目标", "秘密", "成长弧线", "其他备注")

    val REL_FIELDS = arrayOf(
        "甲方", "乙方", "关系类型", "关系性质", "甲方对乙方称谓", "乙方对甲方称谓", "备注")

    val OUTLINE_FIELDS = arrayOf(
        "章节", "标题", "时间", "人物", "地点", "事件", "冲突/看点", "结尾钩子", "备注")

    private val CHAPTER_HEAD: Pattern = Pattern.compile(
        "^[ \\t]*(?:第\\s*[0-9零一二三四五六七八九十百千万两]+\\s*[章回节卷]|序章|楔子|引子|终章|尾声|Chapter\\s+\\d+)[^\\n]*$",
        Pattern.MULTILINE or Pattern.CASE_INSENSITIVE)

    // ------------------------------------------------------------ 基础 IO

    /** 是否可写公共 Download（Android 11+ 需「所有文件访问」授权）。 */
    fun hasAllFilesAccess(ctx: android.content.Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 30 ||
                android.os.Environment.isExternalStorageManager()

    /** 打开「所有文件访问」授权页。 */
    fun openAllFilesSettings(ctx: android.content.Context) {
        try {
            val uri = android.net.Uri.fromParts("package", ctx.packageName, null)
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } catch (e2: Exception) {
                Store.setStatus("无法打开授权页，请到系统设置手动授予「所有文件访问」权限。")
            }
        }
    }

    private fun copyDir(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { f ->
                copyDir(f, File(dst, f.name))
            }
        } else if (!dst.exists()) {
            try { src.copyTo(dst) } catch (e: Exception) { /* 跳过失败的文件 */ }
        }
    }

    /**
     * 项目根目录：已授权「所有文件访问」时用公共 Download/NovelStudio/projects（用户可直接
     * 用文件管理器/电脑操作）；否则回退到应用专属目录。旧目录中的已有项目会自动迁移过来。
     */
    fun initRoot(ctx: android.content.Context): File {
        val legacy = File(ctx.getExternalFilesDir(null), "projects")
        val base: File = if (hasAllFilesAccess(ctx))
            File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS),
                "NovelStudio/projects")
        else legacy
        if (base != legacy && legacy.isDirectory && legacy.list()?.isNotEmpty() == true) {
            copyDir(legacy, base)   // 只补缺，不覆盖
        }
        if (!base.exists()) base.mkdirs()
        Store.rootDir = base
        return base
    }

    fun readText(f: File?, default: String = ""): String {
        if (f == null || !f.exists()) return default
        return try { f.readText(StandardCharsets.UTF_8) } catch (e: Exception) { default }
    }

    fun writeText(f: File, text: String) {
        f.parentFile?.mkdirs()
        f.writeText(text, StandardCharsets.UTF_8)
    }

    fun clip(text: String?, limit: Int = 9000): String {
        val t = (text ?: "").trim()
        return if (t.length <= limit) t else t.substring(0, limit) + "\n…（内容过长已截断）"
    }

    fun chName(n: Int): String = "ch" + "%02d".format(n)

    fun chNumOf(fileName: String): Int? {
        val m = Regex("^ch(\\d+)\\.md$").find(fileName) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    fun emptyRec(fields: Array<String>): Rec {
        val r = LinkedHashMap<String, String>()
        fields.forEach { r[it] = "" }
        return r
    }

    // ------------------------------------------------------------ 项目管理

    fun listProjects(): List<String> {
        val root = Store.rootDir ?: return emptyList()
        return root.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    }

    fun paths(pdir: File): Map<String, File> {
        val m = HashMap<String, File>()
        m["config"] = File(pdir, "project.json")
        m["samples"] = File(pdir, "samples")
        m["style"] = File(pdir, "materials/style_notes.md")
        m["chars"] = File(pdir, "materials/characters.json")
        m["rel"] = File(pdir, "materials/relationships.json")
        m["outline"] = File(pdir, "materials/outline.json")
        m["instance"] = File(pdir, "materials/deconstruct_instance.md")
        m["summaries"] = File(pdir, "chapters/summaries")
        m["drafts"] = File(pdir, "chapters/drafts")
        m["export"] = File(pdir, "chapters/全文.md")
        return m
    }

    fun projectFile(kind: String): File? = Store.projectDir?.let { paths(it)[kind] }

    fun sampleFiles(): List<File> {
        val d = projectFile("samples") ?: return emptyList()
        return d.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
    }

    fun initProjectDirs(pdir: File) {
        listOf("samples", "materials", "chapters/summaries", "chapters/drafts").forEach {
            File(pdir, it).mkdirs()
        }
        val cfgp = File(pdir, "project.json")
        if (!cfgp.exists()) {
            writeText(cfgp, JSONObject().apply {
                put("base_url", Store.cfg.baseUrl)
                put("api_key", Store.cfg.apiKey)
                put("model", Store.cfg.model)
                put("temperature", Store.cfg.temperature)
                put("max_tokens", Store.cfg.maxTokens)
            }.toString(2))
        }
        val style = File(pdir, "materials/style_notes.md")
        if (!style.exists()) writeText(style, "（尚未提炼文风。先在「2.范文提炼文风」页添加范文并点「AI 提炼文风特征」。）")
        val rel = File(pdir, "materials/relationships.json")
        if (!rel.exists()) writeText(rel, JSONArray().put(JSONObject()).toString(2))
        val outline = File(pdir, "materials/outline.json")
        if (!outline.exists()) {
            val o = JSONObject()
            OUTLINE_FIELDS.forEach { o.put(it, if (it == "章节") 1 else "") }
            writeText(outline, JSONArray().put(o).toString(2))
        }
    }

    /** 新建（或直接打开已存在的）项目并加载。 */
    fun openProject(pdir: File) {
        initProjectDirs(pdir)
        Store.projectDir = pdir
        val P = paths(pdir)
        val cfg = ApiConfig()
        try {
            val o = JSONObject(readText(P["config"], "{}"))
            cfg.baseUrl = o.optString("base_url", cfg.baseUrl)
            cfg.apiKey = o.optString("api_key", "")
            cfg.model = o.optString("model", cfg.model)
            cfg.temperature = o.optString("temperature", "0.8")
            cfg.maxTokens = o.optString("max_tokens", "8192")
        } catch (e: Exception) {}
        Store.cfg = cfg

        Store.chars = mutableListOf()
        try {
            val arr = JSONArray(readText(P["chars"], "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                Store.chars.add(recFromJson(o, CHAR_FIELDS))
            }
        } catch (e: Exception) {}
        loadRels()
        loadOutline()
        Store.setStatus("项目已打开：" + pdir.absolutePath)
    }

    fun saveConfig() {
        val f = projectFile("config") ?: return
        writeText(f, JSONObject().apply {
            put("base_url", Store.cfg.baseUrl)
            put("api_key", Store.cfg.apiKey)
            put("model", Store.cfg.model)
            put("temperature", Store.cfg.temperature)
            put("max_tokens", Store.cfg.maxTokens)
        }.toString(2))
        Store.setStatus("设置已保存到 project.json")
    }

    // ------------------------------------------------------------ 素材读写

    private fun recFromJson(o: JSONObject, fields: Array<String>): Rec {
        val r = emptyRec(fields)
        fields.forEach { r[it] = o.optString(it, "") ?: "" }
        return r
    }

    private fun recFromMap(m: Map<*, *>, fields: Array<String>): Rec {
        val r = emptyRec(fields)
        fields.forEach { r[it] = m[it]?.toString() ?: "" }
        return r
    }

    fun recToJson(r: Rec): JSONObject {
        val o = JSONObject()
        r.forEach { (k, v) -> o.put(k, v) }
        return o
    }

    fun loadRels() {
        Store.rels = mutableListOf()
        try {
            val arr = JSONArray(readText(projectFile("rel"), "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                Store.rels.add(recFromJson(o, REL_FIELDS))
            }
        } catch (e: Exception) {}
    }

    fun saveRels() {
        val f = projectFile("rel") ?: return
        val arr = JSONArray()
        Store.rels.forEach { arr.put(recToJson(it)) }
        writeText(f, arr.toString(2))
    }

    fun loadOutline() {
        Store.outline = mutableListOf()
        try {
            val arr = JSONArray(readText(projectFile("outline"), "[]"))
            val list = mutableListOf<Rec>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(recFromJson(o, OUTLINE_FIELDS))
            }
            Store.outline = normalizeOutline(list).toMutableList()
        } catch (e: Exception) {}
    }

    fun saveOutline() {
        val f = projectFile("outline") ?: return
        Store.outline = normalizeOutline(Store.outline).toMutableList()
        val arr = JSONArray()
        Store.outline.forEach { arr.put(recToJson(it)) }
        writeText(f, arr.toString(2))
    }

    fun saveChars() {
        val f = projectFile("chars") ?: return
        val arr = JSONArray()
        Store.chars.forEach { arr.put(recToJson(it)) }
        writeText(f, arr.toString(2))
    }

    fun existingSummaries(): List<Int> {
        val d = projectFile("summaries") ?: return emptyList()
        return d.listFiles { f -> f.isFile }
            ?.mapNotNull { chNumOf(it.name) }
            ?.sorted() ?: emptyList()
    }

    /** 摘要/正文页可选章节 = 已有摘要 ∪ 大纲中的章节（新增大纲章尚未生成摘要也能选）。 */
    fun allChapterNums(): List<Int> {
        val nums = existingSummaries().toMutableSet()
        Store.outline.forEach { it["章节"]?.toIntOrNull()?.let { n -> nums.add(n) } }
        return nums.sorted()
    }

    fun summaryFile(n: Int): File? = projectFile("summaries")?.let { File(it, chName(n) + ".md") }
    fun draftFile(n: Int): File? = projectFile("drafts")?.let { File(it, chName(n) + ".md") }

    // ------------------------------------------------------------ 文本解析

    /** 解析「第N章摘要：……」格式 -> {N: 摘要} */
    fun parseSummaries(text: String): Map<Int, String> {
        val result = LinkedHashMap<Int, String>()
        val re = Regex("第\\s*(\\d+)\\s*章摘要\\s*[:：]\\s*(.+?)(?=第\\s*\\d+\\s*章摘要|\\Z)", RegexOption.DOT_MATCHES_ALL)
        for (m in re.findAll(text)) {
            m.groupValues[1].toIntOrNull()?.let { result[it] = m.groupValues[2].trim() }
        }
        return result
    }

    /** 按段落边界把长文本切块（超长段落硬切），供 AI 分段通读。 */
    fun splitChunks(text: String, size: Int = 6000, maxChunks: Int = 30): List<String> {
        val paras = text.replace("\r\n", "\n").split("\n")
        val chunks = mutableListOf<String>()
        var cur = StringBuilder()
        for (p0 in paras) {
            var p = p0
            while (p.length > size) {
                if (cur.isNotEmpty()) { chunks.add(cur.toString()); cur = StringBuilder() }
                chunks.add(p.substring(0, size))
                p = p.substring(size)
            }
            if (cur.isNotEmpty() && cur.length + p.length + 1 > size) {
                chunks.add(cur.toString()); cur = StringBuilder(p)
            } else {
                if (cur.isNotEmpty()) cur.append("\n")
                cur.append(p)
            }
        }
        if (cur.isNotEmpty()) chunks.add(cur.toString())
        return if (chunks.size > maxChunks) chunks.subList(0, maxChunks) else chunks
    }

    /** 按章节标题把原文切成章节列表；检测不到标题则整篇作为一章。 */
    fun splitChapters(text0: String): List<String> {
        val text = text0.replace("\r\n", "\n").trim()
        val heads = CHAPTER_HEAD.matcher(text).let { m ->
            val list = mutableListOf<IntRange>()
            while (m.find()) list.add(m.start() until m.end())
            list
        }
        if (heads.isEmpty()) return if (text.isEmpty()) emptyList() else listOf(text)
        val parts = mutableListOf<String>()
        for (i in heads.indices) {
            val start = heads[i].first
            val end = if (i + 1 < heads.size) heads[i + 1].first else text.length
            parts.add(text.substring(start, end).trim())
        }
        val pre = text.substring(0, heads[0].first).trim()
        if (pre.isNotEmpty() && pre.length > 200 && parts.isNotEmpty()) {
            parts[0] = pre + "\n\n" + parts[0]
        }
        return parts
    }

    /** 提取 Markdown 中标题含 keyword 的「## 章节」正文。 */
    fun extractMdSection(text: String, keyword: String): String {
        val re = Regex("^##[^\\n]*" + Regex.escape(keyword) + "[^\\n]*$\\n(.*?)(?=^##\\s|\\Z)",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
        return re.find(text)?.groupValues?.get(1)?.trim() ?: ""
    }

    /** 自由文本关系描述按行解析为结构化记录。 */
    fun parseRelString(text: String): List<Rec> {
        val recs = mutableListOf<Rec>()
        for (line0 in text.split("\n")) {
            val line = line0.trim().trimStart('-', '*', '·').trim()
            if (line.isEmpty()) continue
            val rec = emptyRec(REL_FIELDS)
            val m = Regex("(.+?)\\s*(?:——|—|->|=>)\\s*(.+?)\\s*[：:]\\s*(.+)").find(line)
            if (m != null) {
                rec["甲方"] = m.groupValues[1].trim()
                rec["乙方"] = m.groupValues[2].trim()
                rec["关系类型"] = m.groupValues[3].trim()
            } else {
                rec["备注"] = line
            }
            recs.add(rec)
        }
        return recs
    }

    /** 统一把 AI 输出/文件内容转为关系记录列表。 */
    fun toRelRecords(v: Any?): List<Rec> {
        val recs = mutableListOf<Rec>()
        when (v) {
            is String -> recs.addAll(parseRelString(v))
            is List<*> -> for (item in v) {
                when (item) {
                    is Map<*, *> -> recs.add(recFromMap(item, REL_FIELDS))
                    is JSONObject -> recs.add(recFromJson(item, REL_FIELDS))
                    else -> recs.addAll(parseRelString(item.toString()))
                }
            }
        }
        return recs.filter { r -> r.any { (k, v) -> k != "备注" && v.isNotBlank() } || r["备注"]!!.isNotBlank() }
    }

    /** 把任意输入转为大纲记录列表，并按章节排序重编号。 */
    fun toOutlineRecords(v: Any?): List<Rec> {
        val recs = mutableListOf<Rec>()
        var idx = 1
        when (v) {
            is List<*> -> for (item in v) {
                when (item) {
                    is Map<*, *> -> {
                        val r = recFromMap(item, OUTLINE_FIELDS)
                        r["章节"] = Regex("\\d+").find(r["章节"] ?: "")?.value ?: idx.toString()
                        recs.add(r)
                    }
                    is JSONObject -> {
                        val r = recFromJson(item, OUTLINE_FIELDS)
                        r["章节"] = Regex("\\d+").find(r["章节"] ?: "")?.value ?: idx.toString()
                        recs.add(r)
                    }
                    else -> recs.addAll(parseOutlineLine(item.toString()))
                }
                idx++
            }
        }
        return normalizeOutline(recs)
    }

    /** 把大纲行「第N章｜标题｜时间｜…」解析为结构化记录（兼容旧 6 段格式）。 */
    fun parseOutlineLine(line: String): List<Rec> {
        val parts = line.split("｜").map { it.trim() }
        if (parts.isEmpty()) return emptyList()
        val rec = emptyRec(OUTLINE_FIELDS)
        val head = parts[0]
        var rest = parts.drop(1)
        val num = Regex("\\d+").find(head)
        if (num != null && (head.contains("章") || head.all { it.isDigit() })) {
            rec["章节"] = num.value
        } else {
            rest = parts
        }
        val keys = if (rest.size == 6) OUTLINE_FIELDS.slice(2..7) else OUTLINE_FIELDS.slice(1..7)
        for ((k, v) in keys.zip(rest)) rec[k] = v
        return listOf(rec)
    }

    /** 按章节号排序并重新编号（从 1 连续）。 */
    fun normalizeOutline(recs: List<Rec>): List<Rec> {
        val sorted = recs.sortedBy { it["章节"]?.toIntOrNull() ?: 0 }
        return sorted.mapIndexed { i, r -> val r2 = LinkedHashMap(r); r2["章节"] = (i + 1).toString(); r2 }
    }

    /** 从拆解实例中提取 ```json 结构化数据块。 */
    fun extractInstanceJson(text: String): JSONObject? {
        val re = Regex("```json\\s*(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL)
        for (m in re.findAll(text).toList().reversed()) {
            try {
                return JSONObject(m.groupValues[1])
            } catch (e: Exception) { continue }
        }
        return null
    }

    /** JSON 块缺失时，从 Markdown 实例中兜底解析人物与大纲。 */
    fun parseInstanceMd(text: String): Pair<MutableList<Rec>, MutableList<String>> {
        val chars = mutableListOf<Rec>()
        val outlineLines = mutableListOf<String>()
        val reOutline = Regex("^第\\s*\\d+\\s*章\\s*[｜|].*$", RegexOption.MULTILINE)
        outlineLines.addAll(reOutline.findAll(text).map { it.value.trim() })
        val headRe = Regex("^###\\s*【(.+?)】\\s*$", RegexOption.MULTILINE)
        val heads = headRe.findAll(text).toList()
        for (i in heads.indices) {
            val c = emptyRec(CHAR_FIELDS)
            c["姓名"] = heads[i].groupValues[1].trim()
            val start = heads[i].range.last + 1
            val end = if (i + 1 < heads.size) heads[i + 1].range.first else text.length
            for (line in text.substring(start, end).split("\n")) {
                val m = Regex("[-*\\s]*([^：:]+)[：:]\\s*(.+)").find(line.trim())
                if (m != null) {
                    val k = m.groupValues[1].trim()
                    if (k in c && k != "姓名") c[k] = m.groupValues[2].trim()
                }
            }
            chars.add(c)
        }
        return Pair(chars, outlineLines)
    }

    /** json 优先解析拆解实例；json 缺字段时从 Markdown 对应章节兜底提取。 */
    data class InstanceData(
        val style: String, val theme: String,
        val characters: List<Rec>, val relationships: Any?, val outline: Any?)

    fun normalizeInstance(text: String): InstanceData {
        var style = ""
        var theme = ""
        var characters: Any? = null
        var relationships: Any? = null
        var outline: Any? = null
        extractInstanceJson(text)?.let { j ->
            style = j.optString("style", "")
            theme = j.optString("theme", "")
            characters = j.optJSONArray("characters")
            relationships = j.optJSONArray("relationships") ?: j.optString("relationships", "")
            outline = j.optJSONArray("outline")
        }
        if (characters == null || (characters is JSONArray && (characters as JSONArray).length() == 0)) {
            val (mdChars, _) = parseInstanceMd(text)
            if (mdChars.isNotEmpty()) characters = mdChars
        }
        if (style.isBlank()) style = extractMdSection(text, "文风特征")
        if (theme.isBlank()) theme = extractMdSection(text, "基本盘").ifBlank { extractMdSection(text, "题材") }
        if (relationships == null || (relationships is String && (relationships as String).isBlank())) {
            relationships = extractMdSection(text, "人物关系")
        }
        if (outline == null) {
            val (_, lines) = parseInstanceMd(text)
            if (lines.isNotEmpty()) outline = lines
        }
        val charsFinal = mutableListOf<Rec>()
        when (characters) {
            is JSONArray -> {
                val arr = characters as JSONArray
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("姓名").isNotBlank()) charsFinal.add(recFromJson(o, CHAR_FIELDS))
                }
            }
            is List<*> -> (characters as List<*>).forEach { c ->
                when (c) {
                    is Map<*, *> -> {
                        val rec = recFromMap(c, CHAR_FIELDS)
                        if (rec["姓名"]!!.isNotBlank()) charsFinal.add(rec)
                    }
                    is JSONObject -> if (c.optString("姓名").isNotBlank()) charsFinal.add(recFromJson(c, CHAR_FIELDS))
                    else -> {}
                }
            }
        }
        val relsFinal = toRelRecords(relationships)
        val outlineFinal = toOutlineRecords(outline)
        return InstanceData(style, theme, charsFinal, relsFinal, outlineFinal)
    }

    /** 把拆解数据落盘为项目素材文件，返回统计。 */
    fun writeMaterials(data: InstanceData): Map<String, Int> {
        val P = Store.projectDir?.let { paths(it) } ?: return emptyMap()
        var styleMd = ""
        if (data.style.isNotBlank()) styleMd += "## 文风特征\n\n${data.style}\n"
        if (data.theme.isNotBlank()) styleMd += "\n## 题材与情感基调\n\n${data.theme}\n"
        if (styleMd.isNotBlank()) writeText(P["style"]!!, styleMd)
        if (data.characters.isNotEmpty()) {
            val arr = JSONArray(); data.characters.forEach { arr.put(recToJson(it)) }
            writeText(P["chars"]!!, arr.toString(2))
            Store.chars = data.characters.toMutableList()
        }
        if ((data.relationships as? List<*>)?.isNotEmpty() == true) {
            val arr = JSONArray(); (data.relationships as List<Rec>).forEach { arr.put(recToJson(it)) }
            writeText(P["rel"]!!, arr.toString(2))
            Store.rels = (data.relationships as List<Rec>).toMutableList()
        }
        if ((data.outline as? List<*>)?.isNotEmpty() == true) {
            Store.outline = (data.outline as List<Rec>).toMutableList()
            saveOutline()
        }
        return mapOf(
            "n_chars" to data.characters.size,
            "n_rels" to ((data.relationships as? List<*>)?.size ?: 0),
            "n_outline" to ((data.outline as? List<*>)?.size ?: 0))
    }

    // ------------------------------------------------------------ 素材上下文（提示词）

    fun styleText(): String = clip(readText(projectFile("style")))

    fun charsText(names: Set<String>? = null): String {
        val list = if (names != null) {
            val filtered = Store.chars.filter { (it["姓名"] ?: "").trim() in names }
            if (filtered.isEmpty()) return "（本章未检测到已录入人物，请径直按摘要写作）"
            filtered
        } else Store.chars
        if (list.isEmpty()) return "（尚未录入人物）"
        val lines = mutableListOf<String>()
        for (c in list) {
            lines.add("【${c["姓名"] ?: "未命名"}】")
            for (k in CHAR_FIELDS.drop(1)) {
                val v = c[k] ?: ""
                if (v.isNotBlank()) lines.add("  $k：$v")
            }
        }
        return clip(lines.joinToString("\n"))
    }

    fun relText(names: Set<String>? = null): String {
        val list = if (names != null) {
            val filtered = Store.rels.filter {
                (it["甲方"] ?: "").trim() in names && (it["乙方"] ?: "").trim() in names
            }
            if (filtered.isEmpty()) return "（本章未检测到相关人物关系）"
            filtered
        } else Store.rels.toList()
        if (list.isEmpty()) return "（尚未录入人物关系）"
        val lines = mutableListOf<String>()
        for (r in list) {
            val parts = mutableListOf(
                "${r["甲方"] ?: ""} —— ${r["乙方"] ?: ""}：${r["关系类型"] ?: ""}")
            if (!r["关系性质"].isNullOrBlank()) parts.add("（${r["关系性质"]}）")
            if (!r["甲方对乙方称谓"].isNullOrBlank())
                parts.add("${r["甲方"]}称${r["乙方"]}为「${r["甲方对乙方称谓"]}」")
            if (!r["乙方对甲方称谓"].isNullOrBlank())
                parts.add("${r["乙方"]}称${r["甲方"]}为「${r["乙方对甲方称谓"]}」")
            if (!r["备注"].isNullOrBlank()) parts.add("备注：" + r["备注"])
            lines.add(parts.joinToString("，"))
        }
        return clip(lines.joinToString("\n"))
    }

    /** 检测本章摘要中出现了哪些已录入人物（按姓名/称谓匹配）。 */
    fun namesInSummary(text: String): Set<String> {
        if (text.isEmpty() || Store.chars.isEmpty()) return emptySet()
        val alias = HashMap<String, MutableSet<String>>()
        for (r in Store.rels) {
            val a = (r["甲方"] ?: "").trim()
            val b = (r["乙方"] ?: "").trim()
            if (a.isNotEmpty() && !r["甲方对乙方称谓"].isNullOrBlank())
                alias.getOrPut(r["甲方对乙方称谓"]!!.trim()) { mutableSetOf() }.add(b)
            if (b.isNotEmpty() && !r["乙方对甲方称谓"].isNullOrBlank())
                alias.getOrPut(r["乙方对甲方称谓"]!!.trim()) { mutableSetOf() }.add(a)
        }
        val hit = mutableSetOf<String>()
        for (c in Store.chars) {
            val name = (c["姓名"] ?: "").trim()
            if (name.isEmpty()) continue
            if (name in text) { hit.add(name); continue }
            for ((al, targets) in alias) {
                if (al.isNotEmpty() && al in text && name in targets) { hit.add(name); break }
            }
        }
        return hit
    }

    fun outlineText(): String {
        if (Store.outline.isEmpty()) return "（尚未录入剧情大纲）"
        val lines = Store.outline.map {
            "第${it["章节"]}章｜${it["标题"]}｜${it["时间"]}｜${it["人物"]}｜${it["地点"]}｜${it["事件"]}｜${it["冲突/看点"]}｜${it["结尾钩子"]}"
        }
        return clip(lines.joinToString("\n"), 12000)
    }

    fun sumContext(): String =
        "【文风特征】\n${styleText()}\n\n【人物档案】\n${charsText()}\n\n【人物关系】\n${relText()}\n\n【剧情大纲】\n${outlineText()}"

    // ---------------- 第 6 页：本章摘要提示词（透明化，可编辑） ----------------

    private fun outlineChapter(n: Int): Rec? =
        Store.outline.firstOrNull { (it["章节"] ?: "").trim() == n.toString() }

    private fun outlineLine(r: Rec?): String? {
        if (r == null) return null
        return "第${r["章节"]}章｜${r["标题"]}｜${r["时间"]}｜${r["人物"]}｜${r["地点"]}｜${r["事件"]}｜${r["冲突/看点"]}｜${r["结尾钩子"]}"
    }

    /** 生成「第 n 章摘要」的提示词：透明展示如何从上一环节的剧情大纲生成本章摘要，
     *  在第 6 页提示词编辑框中展示，用户可修改后直接执行。 */
    fun summaryPrompt(n: Int): String {
        val cur = outlineChapter(n)
        // 本章出场人物：大纲「人物」字段与人物档案求交，只带本章相关档案/关系
        val people = (cur?.get("人物") ?: "")
            .split(Regex("[、，,;；/\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val charNames = Store.chars.map { (it["姓名"] ?: "").trim() }.filter { it.isNotEmpty() }.toSet()
        val hitNames = people.filter { it in charNames }.toSet()
        val charsMd = if (hitNames.isNotEmpty()) charsText(hitNames) else charsText()
        val relMd = if (hitNames.isNotEmpty()) relText(hitNames) else relText()
        val curLine = outlineLine(cur)
            ?: "（剧情大纲中暂无第 $n 章，请根据人物档案与前后文合理撰写本章内容）"
        val prevLine = if (n == 1)
            "（本章为第 1 章，无上一章；可在摘要开头适当交代开篇背景）"
        else
            outlineLine(outlineChapter(n - 1))
                ?: "（大纲中暂无第 ${n - 1} 章；请保证与已有前文自然衔接）"
        return buildString {
            append("任务：根据「第 $n 章大纲」撰写本章摘要（300~400 字）。\n\n")
            append("【生成方法】\n")
            append("1. 以「第 $n 章大纲」为主体：把时间地点、出场人物、核心事件、冲突/看点、结尾钩子扩写成连贯的摘要叙述；\n")
            append("2. 参考「上一章大纲」保证与前文自然衔接；\n")
            append("3. 出场人物以大纲「人物」字段为准：其性别、年龄、身份、性格等基本属性取自下方人物档案，双方称谓与关系取自人物关系表，不得张冠李戴；\n")
            append("4. 摘要文字风格参照文风特征；\n")
            append("5. 摘要须完整包含：时间地点、出场人物、核心事件、剧情看点/冲突、本章结尾悬念（钩子）。\n\n")
            append("【文风特征】\n${styleText()}\n\n")
            append("【人物档案】（本章出场人物）\n$charsMd\n\n")
            append("【人物关系】（本章相关）\n$relMd\n\n")
            append("【上一章大纲】（衔接参考）\n$prevLine\n\n")
            append("【第 $n 章大纲】（摘要主体）\n$curLine\n\n")
            append("输出格式：第${n}章摘要：<内容>（不要其他说明文字）。")
        }
    }
}
