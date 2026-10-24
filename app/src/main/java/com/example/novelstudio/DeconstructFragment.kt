package com.example.novelstudio

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DeconstructFragment : Fragment() {

    private lateinit var spinSample: Spinner
    private lateinit var etReuse: EditText
    private lateinit var etInstance: EditText

    private val pickSample =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val dir = Repo.projectFile("samples") ?: return@registerForActivityResult
                try {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "sample.txt"
                    requireContext().contentResolver.openInputStream(uri)?.use { ins ->
                        Repo.writeText(File(dir, name), ins.readBytes().toString(Charsets.UTF_8))
                    }
                    refreshSamples(name)
                    Store.setStatus("已导入范文：$name")
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_deconstruct, container, false)
        spinSample = v.findViewById(R.id.spin_dsample)
        etReuse = v.findViewById(R.id.et_reuse)
        etInstance = v.findViewById(R.id.et_instance)

        v.findViewById<Button>(R.id.btn_import_sample).setOnClickListener {
            if (ensureProject()) pickSample.launch(arrayOf("text/*", "text/plain"))
        }
        v.findViewById<Button>(R.id.btn_deconstruct).setOnClickListener { deconstruct() }
        v.findViewById<Button>(R.id.btn_save_instance).setOnClickListener {
            if (ensureProject()) {
                Repo.writeText(Repo.projectFile("instance")!!, etInstance.text.toString())
                Store.setStatus("拆解实例已保存到 materials/deconstruct_instance.md")
            }
        }
        v.findViewById<Button>(R.id.btn_rewrite).setOnClickListener { rewriteInstance() }
        v.findViewById<Button>(R.id.btn_apply_instance).setOnClickListener { applyInstance() }

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Changes.version.collect { if (isAdded) refresh() }
            }
        }
        refresh()
        return v
    }

    fun refresh() {
        refreshSamples(null)
        etInstance.setText(Repo.readText(Repo.projectFile("instance")))
    }

    private fun refreshSamples(select: String?) {
        val names = Repo.sampleFiles().map { it.name }
        spinSample.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, names)
        select?.let {
            val idx = names.indexOf(it)
            if (idx >= 0) spinSample.setSelection(idx)
        }
    }

    private fun ensureProject(): Boolean {
        if (Store.projectDir == null) {
            Toast.makeText(requireContext(), "请先在「1.设置与项目」新建或打开项目。", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun rewriteInstance() {
        if (!ensureProject()) return
        val inst = etInstance.text.toString().trim()
        val instr = etReuse.text.toString().trim()
        if (inst.isEmpty()) { toast("请先完成『AI 通读拆解』。"); return }
        if (instr.isEmpty()) { toast("请先填写仿写设定，例如：主角改为女法医沈青，背景改为滨海市。"); return }
        val ok = Jobs.run("按仿写设定改写实例") {
            val system = "你是小说改编策划，负责在保留情节结构的前提下替换设定。"
            val user = ("以下是一份范文拆解实例（Markdown + json 块）。\n\n${Repo.clip(inst, 16000)}\n\n"
                    + "仿写设定：$instr\n\n"
                    + "要求：保持整体结构、章节划分、Markdown 格式与 json 字段完全不变，"
                    + "只替换人物姓名/身份背景/地点时代等设定，情节结构与冲突点保留；"
                    + "json 块内容须与正文一致。只输出改写后的完整实例。")
            val content = LlmClient.call(
                Store.cfg.copy(),
                listOf(LlmClient.ChatMessage("system", system), LlmClient.ChatMessage("user", user)))
            Repo.writeText(Repo.projectFile("instance")!!, content.trim())
            withContext(Dispatchers.Main) {
                if (isAdded) etInstance.setText(content.trim())
            }
        }
        if (!ok) toast("上一次 AI 任务还在进行中，请稍候。")
    }

    private fun applyInstance() {
        if (!ensureProject()) return
        val text = etInstance.text.toString()
        if (text.isBlank()) { toast("当前没有拆解实例。"); return }
        val data = Repo.normalizeInstance(text)
        if (data.characters.isEmpty() && (data.outline as? List<*>).isNullOrEmpty()) {
            toast("未能从实例中解析出人物或大纲，请检查实例内容（或重新拆解）。")
            return
        }
        val stats = Repo.writeMaterials(data)
        Changes.bump()
        Store.setStatus(
            "拆解实例已应用到项目素材：文风 ${data.style.length} 字，人物 ${data.characters.size} 人，大纲 ${(data.outline as? List<*>)?.size ?: 0} 章。")
        AlertDialog.Builder(requireContext())
            .setTitle("完成")
            .setMessage("已应用到项目素材：\n· 文风特征（第2页）\n· 人物档案 ${data.characters.size} 人（第3页，请核对）\n"
                    + "· 人物关系 ${stats["n_rels"]} 条（第4页）\n· 剧情大纲 ${stats["n_outline"]} 章（第5页）\n\n"
                    + "现在可到第 6 页生成章节摘要，再到第 7 页逐章生成正文。")
            .setPositiveButton("好的", null)
            .show()
    }

    private fun deconstruct() {
        if (!ensureProject()) return
        val names = Repo.sampleFiles().map { it.name }
        val sel = spinSample.selectedItemPosition
        if (sel < 0 || sel >= names.size) { toast("请先选择或导入范文 txt。"); return }
        val fn = names[sel]
        val file = File(Repo.projectFile("samples"), fn)
        val fullText = Repo.readText(file)
        if (fullText.isBlank()) { toast("范文内容为空。"); return }

        val ok = Jobs.run("开始通读拆解《$fn》") {
            val cfg = Store.cfg.copy().apply {
                maxTokens = (maxTokens.toIntOrNull()?.coerceAtLeast(8192) ?: 8192).toString()
            }
            val chapters = Repo.splitChapters(fullText)
            var headLines = ""
            if (chapters.size >= 2) {
                val titles = chapters.take(80).mapIndexed { i, c ->
                    "第${i + 1}章：" + c.lineSequence().firstOrNull()?.take(40).orEmpty()
                }
                headLines = ("\n\n=== 原文检测到的章节划分（共 ${chapters.size} 章，"
                        + "剧情大纲必须严格按此划分，一章一条） ===\n" + titles.joinToString("\n"))
            }
            val chunks = Repo.splitChunks(fullText)
            val analyses = mutableListOf<String>()
            for ((i, ch) in chunks.withIndex()) {
                Store.setStatus("AI 通读第 ${i + 1}/${chunks.size} 段…")
                analyses.add(LlmClient.call(cfg, listOf(
                    LlmClient.ChatMessage("system", "你是资深小说编辑，正在为范文拆解做准备，只做客观提取。"),
                    LlmClient.ChatMessage("user", (
                            "以下是小说《$fn》的第 ${i + 1}/${chunks.size} 段。请提取：\n"
                                    + "1. 本段出场人物：姓名、身份职务、外貌/性格/语言风格线索（尽量引用原文依据）；\n"
                                    + "2. 人物关系及其变化；\n"
                                    + "3. 本段情节：按场景顺序逐条列出，每条一行：时间｜人物｜地点｜事件｜冲突或看点｜留下的悬念。\n"
                                    + "只输出提取内容，不要评论。\n\n$ch")))))
            }
            Store.setStatus("分段通读完成，正在汇总拆解…")
            val merged = LlmClient.call(cfg, listOf(
                LlmClient.ChatMessage("system", "你是资深小说编辑，负责把范文完整拆解为可复用的创作模板。"),
                LlmClient.ChatMessage("user", (
                        "以下是范文《$fn》的分段分析结果，以及原文开头（供提炼文风）。\n\n"
                                + "=== 原文开头 ===\n${Repo.clip(fullText, 6000)}\n\n"
                                + "=== 分段分析 ===\n${Repo.clip(analyses.joinToString("\n\n"), 24000)}$headLines\n\n"
                                + "请输出【两部分】，不要输出其他内容：\n\n"
                                + "【第一部分】范文拆解实例（Markdown），结构如下：\n"
                                + "# 范文拆解实例：《$fn》\n"
                                + "## 一、作品基本盘（题材/篇幅/视角/情感基调）\n"
                                + "## 二、文风特征（视角、句式、对话占比、节奏、高频词、需避免之处）\n"
                                + "## 三、人物档案（每个主要人物一小节，格式必须为：### 【姓名】，"
                                + "其下用『- 字段：内容』列出：性别、年龄、职务/身份、身高体型、样貌、穿着特点、"
                                + "性格特点、语言特点/口头禅、典型台词、动机与目标、秘密、其他备注）\n"
                                + "## 四、人物关系（明面关系与隐藏面、称谓）\n"
                                + "## 五、剧情大纲（严格每章一行：第N章｜时间｜人物｜地点｜事件｜冲突/看点｜结尾钩子。"
                                + "若上方给出了原文章节划分，必须逐章对齐；否则按场景/幕切分为章）\n"
                                + "## 六、仿写要点（用此模板仿写出同类小说最关键的 10 条规则）\n\n"
                                + "【第二部分】紧跟一个 ```json 代码块，供程序读取，字段：\n"
                                + "{\"style\": \"文风特征纯文本\", \"theme\": \"题材与情感基调\", "
                                + "\"characters\": [{\"姓名\":\"\", \"性别\":\"\", \"年龄\":\"\", \"职务/身份\":\"\", \"身高体型\":\"\", "
                                + "\"样貌\":\"\", \"穿着特点\":\"\", \"性格特点\":\"\", \"语言特点/口头禅\":\"\", \"典型台词\":\"\", "
                                + "\"动机与目标\":\"\", \"秘密\":\"\", \"其他备注\":\"\"}], "
                                + "\"relationships\": [{\"甲方\":\"\", \"乙方\":\"\", \"关系类型\":\"\", "
                                + "\"关系性质\":\"公开/隐藏/伪装\", \"甲方对乙方称谓\":\"\", \"乙方对甲方称谓\":\"\", \"备注\":\"\"}], "
                                + "\"outline\": [{\"章节\":1, \"标题\":\"\", \"时间\":\"\", \"人物\":\"\", \"地点\":\"\", "
                                + "\"事件\":\"\", \"冲突/看点\":\"\", \"结尾钩子\":\"\"}]}\n"
                                + "JSON 中 characters/relationships/outline 必须与第一部分对应内容一致；"
                                + "relationships 为数组，每条一组人物关系；outline 为数组，每条对应一章，"
                                + "字段不得缺失（无内容填空字符串）。"))))

            Store.setStatus("正在写入素材文件（文风/人物/关系/大纲/实例）…")
            val data = Repo.normalizeInstance(merged)
            val stats = Repo.writeMaterials(data)
            Repo.writeText(Repo.projectFile("instance")!!, merged.trim())
            for ((i, ctext) in chapters.withIndex()) {
                Repo.writeText(File(Repo.projectFile("drafts"), Repo.chName(i + 1) + ".md"), ctext)
            }
            var nSum = 0
            for ((i, ctext) in chapters.withIndex()) {
                val spath = File(Repo.projectFile("summaries"), Repo.chName(i + 1) + ".md")
                if (i + 1 > 60) {
                    Repo.writeText(
                        spath,
                        "（原文摘要未自动生成：超出单次拆解 60 章上限，可到第 6 页单独生成本章摘要。）")
                    continue
                }
                Store.setStatus("生成第 ${i + 1}/${chapters.size} 章摘要…")
                val out = LlmClient.call(cfg, listOf(
                    LlmClient.ChatMessage("system", "你是资深小说编辑，负责为小说章节撰写规范摘要。"),
                    LlmClient.ChatMessage("user", (
                            "以下是《$fn》第 ${i + 1} 章（共 ${chapters.size} 章）原文。\n\n${Repo.clip(ctext, 6000)}\n\n"
                                    + "请写 200~400 字章节摘要，必须包含：时间地点、出场人物、核心事件、"
                                    + "剧情看点/冲突、结尾钩子。输出格式严格为：第${i + 1}章摘要：<内容>（不要其他文字）。"))))
                val parsed = Repo.parseSummaries(out)
                Repo.writeText(spath, parsed[i + 1] ?: out.trim())
                nSum++
            }
            Changes.bump()

            val hasJson = Repo.extractInstanceJson(merged) != null
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    etInstance.setText(merged.trim())
                    var msg = ("拆解完成，已生成完整项目目录：\n"
                            + "· 文风特征、人物档案 ${stats["n_chars"]} 人、人物关系、剧情大纲 ${stats["n_outline"]} 章（materials/）\n"
                            + "· 拆解实例 deconstruct_instance.md\n"
                            + "· 章节正文 ${chapters.size} 章为原文（chapters/drafts/）\n"
                            + "· AI 章节摘要 $nSum 份（chapters/summaries/）")
                    val nOutline = stats["n_outline"] ?: 0
                    if (nOutline != 0 && nOutline != chapters.size) {
                        msg += ("\n\n注意：AI 大纲为 $nOutline 章，与原文实际章节数（${chapters.size}）不一致，"
                                + "摘要以原文章节为准；可到第 5 页修订大纲。")
                    }
                    if (!hasJson) {
                        msg += "\n\n注意：未检测到 json 块，本次按 Markdown 兜底解析，人物/大纲可能不全。"
                    }
                    msg += ("\n\n仿写方法：实例已即模板。到第 7 页选任意章点「AI 生成本章正文」，"
                            + "AI 会按文风+人物+本章摘要重写出仿写正文（可先填仿写设定改写实例，换人名背景）。")
                    AlertDialog.Builder(requireContext())
                        .setTitle("完成")
                        .setMessage(msg)
                        .setPositiveButton("好的", null)
                        .show()
                }
            }
        }
        if (!ok) toast("上一次 AI 任务还在进行中，请稍候。")
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
