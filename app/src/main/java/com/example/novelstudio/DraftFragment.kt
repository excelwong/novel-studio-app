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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 第 7 页：章节正文（流式生成 + 中止 + 保存 + 导出全文）。 */
class DraftFragment : Fragment() {

    private lateinit var spinDraft: Spinner
    private lateinit var etWordcount: EditText
    private lateinit var cbCarry: CheckBox
    private lateinit var cbScoped: CheckBox
    private lateinit var btnAbort: Button
    private lateinit var txtSumPrev: TextView
    private lateinit var etDraft: EditText
    private var abortFlag: LlmClient.AbortFlag? = null
    private var loadedDraftKey = -1
    private var loadedDraftText = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_draft, container, false)
        spinDraft = v.findViewById(R.id.spin_draft)
        etWordcount = v.findViewById(R.id.et_wordcount)
        cbCarry = v.findViewById(R.id.cb_carry)
        cbScoped = v.findViewById(R.id.cb_scoped)
        btnAbort = v.findViewById(R.id.btn_abort)
        txtSumPrev = v.findViewById(R.id.txt_sum_prev)
        txtSumPrev.movementMethod = android.text.method.ScrollingMovementMethod()
        etDraft = v.findViewById(R.id.et_draft)

        v.findViewById<Button>(R.id.btn_gen_draft).setOnClickListener { genDraft() }
        btnAbort.setOnClickListener { Jobs.abort() }
        v.findViewById<Button>(R.id.btn_save_draft).setOnClickListener { saveDraft() }
        v.findViewById<Button>(R.id.btn_export).setOnClickListener { exportAll() }

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Changes.version.collect { if (isAdded) refresh() }
                }
                // 每次切到本 tab：自动装载当前章节的摘要预览与正文（如有）
                launch {
                    Store.activeTab.collect { if (it == 7 && isAdded) loadSelected() }
                }
                // 第 6 页保存了某章摘要且与本页当前章节一致 → 自动同步预览
                launch {
                    Store.summarySaved.collect { ch ->
                        if (ch != null && isAdded && curChapter() == ch) loadSelected()
                    }
                }
                Store.busy.collect { busy ->
                    if (isAdded) {
                        if (!busy) abortFlag = null
                        btnAbort.isEnabled = busy && abortFlag != null
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
        spinDraft.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        spinDraft.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                loadSelected()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun curChapter(): Int? {
        val pos = spinDraft.selectedItemPosition
        if (pos < 0) return null
        return Repo.allChapterNums().getOrNull(pos)
    }

    /** 装载当前章节：摘要预览总是刷新；正文在本章有未保存修改时不覆盖。 */
    private fun loadSelected() {
        val n = curChapter() ?: return
        txtSumPrev.text = Repo.readText(Repo.summaryFile(n), "（本章摘要缺失，请回「6.章节摘要」生成）")
        val cur = etDraft.text.toString()
        if (loadedDraftKey == n && cur != loadedDraftText) return   // 有未保存修改，保护现场
        loadedDraftKey = n
        loadedDraftText = Repo.readText(Repo.draftFile(n))
        etDraft.setText(loadedDraftText)
    }

    private fun genDraft() {
        if (Store.projectDir == null) {
            toast("请先在「1.设置与项目」新建或打开项目。")
            return
        }
        val n = curChapter() ?: run { toast("请先选择章节（需已生成该章摘要）。"); return }
        val summary = Repo.readText(Repo.summaryFile(n))
        if (summary.isBlank()) { toast("该章摘要为空，请先在「6.章节摘要」生成并保存。"); return }

        var prevEnd = ""
        if (n > 1 && cbCarry.isChecked) {
            val prev = Repo.readText(Repo.draftFile(n - 1))
            if (prev.isNotBlank()) {
                prevEnd = "\n\n【前一章结尾】（衔接用，不要重复原句）\n" + Repo.clip(prev, 900)
            }
        }
        val hit = if (cbScoped.isChecked) Repo.namesInSummary(summary) else emptySet()
        val charsMd: String
        val relMd: String
        val scope: String
        if (hit.isNotEmpty()) {
            charsMd = Repo.charsText(hit)
            relMd = Repo.relText(hit)
            scope = "人物 ${hit.size}/${Store.chars.size}：${hit.joinToString("、")}"
        } else {
            charsMd = Repo.charsText()
            relMd = Repo.relText()
            scope = "未匹配到已录入人物（附全量档案）"
        }
        val system = "你是该连载小说的作者，严格按给定文风与人设写作。"
        val user = ("【文风特征】（必须严格模仿）\n${Repo.styleText()}\n\n"
                + "【人物档案】（言行必须符合人设）\n$charsMd\n\n"
                + "【人物关系】\n$relMd\n\n"
                + "【第 $n 章摘要】\n$summary$prevEnd\n\n"
                + "写作要求：\n1. 正文约 ${etWordcount.text.toString().ifBlank { "2500" }} 字；\n"
                + "2. 严格按本章摘要展开，不得遗漏关键情节；\n"
                + "3. 人物语言符合各自的语言特点；\n"
                + "4. 结尾按摘要设置钩子，吸引读者追更；\n"
                + "5. 只输出正文，章节标题格式为：第${n}章 <标题>，不要任何额外说明。")

        val flag = LlmClient.AbortFlag()
        abortFlag = flag
        val ok = Jobs.run("生成第 $n 章正文（$scope）") {
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    etDraft.setText("")
                    btnAbort.isEnabled = true
                    Store.setStatus("正在流式生成第 $n 章正文（$scope）…可随时点「中止」")
                }
            }
            val content = LlmClient.call(
                Store.cfg.copy(),
                listOf(LlmClient.ChatMessage("system", system), LlmClient.ChatMessage("user", user)),
                stream = true,
                onChunk = { delta ->
                    // IO 线程回调，post 到主线程追加显示
                    if (isAdded) etDraft.post { if (isAdded) etDraft.append(delta) }
                },
                abort = flag)
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    var text = content.trim()
                    // 兜底：剥离模型/解析可能残留的开头 "null" 标记
                    text = text.replaceFirst(Regex("^\\s*null\\b[\\s：:]*", RegexOption.IGNORE_CASE), "")
                    if (!text.startsWith("第${n}章") && !text.take(30).contains("第${n}章")) {
                        text = "第${n}章\n\n$text"
                    }
                    if (etDraft.text.toString().trim() != text) {
                        etDraft.setText(text)
                        etDraft.setSelection(text.length)
                    }
                    loadedDraftKey = n
                    loadedDraftText = text
                    Store.setStatus("第 $n 章正文已生成，请审阅修改后点「保存本章正文」。")
                }
            }
        }
        if (!ok) {
            abortFlag = null
            toast("上一次 AI 任务还在进行中，请稍候。")
        }
    }

    private fun saveDraft() {
        if (Store.projectDir == null) return
        val n = curChapter() ?: return
        val text = etDraft.text.toString()
        Repo.writeText(Repo.draftFile(n)!!, text)
        loadedDraftKey = n
        loadedDraftText = text
        Store.setStatus("第 $n 章正文已保存。")
    }

    private fun exportAll() {
        if (Store.projectDir == null) {
            toast("请先在「1.设置与项目」新建或打开项目。")
            return
        }
        val ddir = Repo.projectFile("drafts")!!
        val files = ddir.listFiles { f -> f.isFile && Repo.chNumOf(f.name) != null }
            ?.sortedBy { Repo.chNumOf(it.name) }
            ?: emptyList()
        if (files.isEmpty()) { toast("尚无任何已保存的正文。"); return }
        val sb = StringBuilder("# " + Store.projectDir!!.name + "\n")
        for (f in files) {
            sb.append("\n\n----\n\n").append(Repo.readText(f))
        }
        val out = Repo.projectFile("export")!!
        Repo.writeText(out, sb.toString())
        Store.setStatus("已导出全文：${out.absolutePath}")
        AlertDialog.Builder(requireContext())
            .setTitle("完成")
            .setMessage("已导出全文（${files.size} 章）：\n${out.absolutePath}")
            .setPositiveButton("好的", null)
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
