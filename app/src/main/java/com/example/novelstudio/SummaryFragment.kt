package com.example.novelstudio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 第 6 页：章节摘要（批量生成 / 单章重生成 / AI 修订 / 保存）。 */
class SummaryFragment : Fragment() {

    private lateinit var etN: EditText
    private lateinit var cbOverwrite: CheckBox
    private lateinit var spinSum: Spinner
    private lateinit var etRevise: EditText
    private lateinit var etSummary: EditText
    private var loadedSumKey = -1
    private var loadedSumText = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_summary, container, false)
        etN = v.findViewById(R.id.et_n_chapters)
        cbOverwrite = v.findViewById(R.id.cb_overwrite)
        spinSum = v.findViewById(R.id.spin_sum)
        etRevise = v.findViewById(R.id.et_revise)
        etSummary = v.findViewById(R.id.et_summary)

        v.findViewById<Button>(R.id.btn_gen_all).setOnClickListener { genAllSummaries() }
        v.findViewById<Button>(R.id.btn_gen_one).setOnClickListener { genOneSummary() }
        v.findViewById<Button>(R.id.btn_revise).setOnClickListener { reviseSummary() }
        v.findViewById<Button>(R.id.btn_save_summary).setOnClickListener { saveSummary() }

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Changes.version.collect { if (isAdded) refresh() }
                }
                // 每次切到本 tab：自动装载当前章节的摘要（如有）
                launch {
                    Store.activeTab.collect { if (it == 6 && isAdded) loadSelected() }
                }
                // 第 6 页保存某章后，同步刷新（含第 7 页预览的跨页通知）
                launch {
                    Store.summarySaved.collect { ch ->
                        if (ch != null && isAdded && curChapter() == ch) loadSelected()
                    }
                }
            }
        }
        refresh()
        return v
    }

    fun refresh() {
        val nums = Repo.allChapterNums()
        val labels = nums.map { "第${it}章" }
        spinSum.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        spinSum.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                loadSelected()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun curChapter(): Int? {
        val pos = spinSum.selectedItemPosition
        if (pos < 0) return null
        return Repo.allChapterNums().getOrNull(pos)
    }

    private fun ensureProject(): Boolean {
        if (Store.projectDir == null) {
            toast("请先在「1.设置与项目」新建或打开项目。")
            return false
        }
        return true
    }

    private fun genAllSummaries() {
        if (!ensureProject()) return
        val n = etN.text.toString().toIntOrNull() ?: 10
        if (n < 1 || n > 200) { toast("规划章数需在 1~200 之间。"); return }
        if (Store.rels.isNotEmpty()) Repo.saveRels()
        if (Store.outline.isNotEmpty()) Repo.saveOutline()

        val ok = Jobs.run("批量生成章节摘要") {
            val system = "你是经验丰富的连载小说策划编辑。"
            val user = (Repo.sumContext() + "\n\n任务：规划并撰写第 1 至第 $n 章的章节摘要。"
                    + "每章摘要 200~400 字，必须包含：时间地点、出场人物、核心事件、"
                    + "剧情看点/冲突、本章结尾悬念（钩子）。大纲已给到哪章就写到哪章；"
                    + "若大纲不足 $n 章，请按主线合理延展。\n\n"
                    + "输出格式必须严格为每章一段（不要其他说明文字）：\n"
                    + "第1章摘要：<内容>\n第2章摘要：<内容>\n……")
            val content = LlmClient.call(
                Store.cfg.copy(),
                listOf(LlmClient.ChatMessage("system", system), LlmClient.ChatMessage("user", user)))
            val parsed = Repo.parseSummaries(content)
            if (parsed.isEmpty()) {
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        etSummary.setText(content)
                        toast("未能从 AI 输出中解析出章节摘要，原文已放入摘要编辑框，请检查模型输出或重试。")
                    }
                }
                return@run
            }
            val skip = mutableListOf<Int>()
            for ((k, text) in parsed) {
                val p = Repo.summaryFile(k) ?: continue
                if (p.exists() && !cbOverwrite.isChecked) { skip.add(k); continue }
                Repo.writeText(p, text)
            }
            Changes.bump()
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    refresh()
                    var msg = "已写入 ${parsed.size - skip.size} 章摘要。"
                    if (skip.isNotEmpty()) msg += "（第 ${skip.joinToString("、")} 章已存在被跳过，可勾选「覆盖已有摘要」重试）"
                    Store.setStatus(msg)
                    toast(msg + " 请务必逐章检查修订后保存。")
                }
            }
        }
        if (!ok) toast("上一次 AI 任务还在进行中，请稍候。")
    }

    private fun genOneSummary() {
        if (!ensureProject()) return
        val n = curChapter() ?: run { toast("请先选择章节。"); return }
        val ok = Jobs.run("生成本章摘要") {
            val system = "你是经验丰富的连载小说策划编辑。"
            val user = (Repo.sumContext() + "\n\n任务：只撰写第 $n 章摘要（300~400 字），包含：时间地点、出场人物、"
                    + "核心事件、剧情看点/冲突、结尾钩子。需与前文自然衔接。"
                    + "输出格式：第${n}章摘要：<内容>（不要其他说明）。")
            val content = LlmClient.call(
                Store.cfg.copy(),
                listOf(LlmClient.ChatMessage("system", system), LlmClient.ChatMessage("user", user)))
            val parsed = Repo.parseSummaries(content)
            val text = parsed[n] ?: content.trim()
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    etSummary.setText(text)
                    Store.setStatus("第 $n 章摘要已生成，请检查修订后点「保存本章摘要」。")
                }
            }
        }
        if (!ok) toast("上一次 AI 任务还在进行中，请稍候。")
    }

    private fun reviseSummary() {
        if (!ensureProject()) return
        val n = curChapter() ?: run { toast("请先选择章节。"); return }
        val instruction = etRevise.text.toString().trim()
        if (instruction.isEmpty()) { toast("请先填写修订指令。"); return }
        val old = etSummary.text.toString().trim()
            .ifBlank { Repo.readText(Repo.summaryFile(n)) }
        // 提示词带上人物档案与关系（按摘要中出现的姓名裁剪），避免 AI 搞错人物基本属性与称谓
        val hit = Repo.namesInSummary(old)
        val charsMd = if (hit.isNotEmpty()) Repo.charsText(hit) else Repo.charsText()
        val relMd = if (hit.isNotEmpty()) Repo.relText(hit) else Repo.relText()
        val ok = Jobs.run("修订摘要") {
            val system = "你是连载小说编辑，负责按作者要求修订章节摘要。"
            val user = ("【人物档案】（修订后人物性别、年龄、身份、性格等基本属性必须与档案一致）\n$charsMd\n\n"
                    + "【人物关系】（双方称谓、关系性质不得弄错，须与关系表一致）\n$relMd\n\n"
                    + "【原摘要】\n$old\n\n【修订要求】$instruction\n\n"
                    + "请输出修订后的完整摘要，保持 200~400 字，保留时间地点/人物/事件/看点/钩子结构；"
                    + "人物姓名、身份、称谓必须与上述档案和关系表一致，不得张冠李戴，只输出摘要正文。")
            val content = LlmClient.call(
                Store.cfg.copy(),
                listOf(LlmClient.ChatMessage("system", system), LlmClient.ChatMessage("user", user)))
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    etSummary.setText(content.trim())
                    Store.setStatus("第 $n 章摘要已按指令修订，确认无误后点「保存本章摘要」。")
                }
            }
        }
        if (!ok) toast("上一次 AI 任务还在进行中，请稍候。")
    }

    /** 装载当前选中章节的摘要；本章有未保存修改时不覆盖。 */
    private fun loadSelected() {
        val n = curChapter() ?: return
        val cur = etSummary.text.toString()
        if (loadedSumKey == n && cur != loadedSumText) return   // 有未保存修改，保护现场
        loadedSumKey = n
        loadedSumText = Repo.readText(Repo.summaryFile(n))
        etSummary.setText(loadedSumText)
    }

    private fun saveSummary() {
        if (!ensureProject()) return
        val n = curChapter() ?: run { toast("请先选择章节。"); return }
        val text = etSummary.text.toString()
        Repo.writeText(Repo.summaryFile(n)!!, text)
        loadedSumKey = n
        loadedSumText = text
        Store.notifySummarySaved(n)   // 通知第 7 页同步预览
        Store.setStatus("第 $n 章摘要已保存。")
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
