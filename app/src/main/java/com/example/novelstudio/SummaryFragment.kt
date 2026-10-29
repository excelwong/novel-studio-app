package com.example.novelstudio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
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

/**
 * 第 6 页：章节摘要（提示词透明化模式）。
 * 选择章节时自动把「如何从剧情大纲生成本章摘要」的完整提示词填入可编辑多行框，
 * 用户可查看/修改后一键执行；生成结果放入下方摘要编辑框，人工修订后保存。
 */
class SummaryFragment : Fragment() {

    private lateinit var spinSum: Spinner
    private lateinit var etPrompt: EditText
    private lateinit var etSummary: EditText
    private var loadedSumKey = -1
    private var loadedSumText = ""
    private var loadedPromptKey = -1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_summary, container, false)
        spinSum = v.findViewById(R.id.spin_sum)
        etPrompt = v.findViewById(R.id.et_prompt)
        etSummary = v.findViewById(R.id.et_summary)

        v.findViewById<Button>(R.id.btn_run_prompt).setOnClickListener { runPrompt() }
        v.findViewById<Button>(R.id.btn_save_summary).setOnClickListener { saveSummary() }

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Changes.version.collect { if (isAdded) refresh() }
                }
                // 每次切到本 tab：自动填充提示词并装载当前章节的摘要（如有）
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

    /** 按提示词编辑框中的内容（用户可改）生成本章摘要。 */
    private fun runPrompt() {
        if (!ensureProject()) return
        val n = curChapter() ?: run { toast("请先选择章节。"); return }
        val prompt = etPrompt.text.toString().trim()
        if (prompt.isEmpty()) { toast("提示词为空，请先选择章节自动填充，或手动填写。"); return }
        val ok = Jobs.run("生成本章摘要") {
            val system = "你是经验丰富的连载小说策划编辑。"
            val content = LlmClient.call(
                Store.cfg.copy(),
                listOf(LlmClient.ChatMessage("system", system), LlmClient.ChatMessage("user", prompt)))
            val parsed = Repo.parseSummaries(content)
            val text = parsed[n] ?: content.trim()
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    etSummary.setText(text)
                    Store.setStatus("第 $n 章摘要已按提示词生成，请检查修订后点「保存本章摘要」。")
                }
            }
        }
        if (!ok) toast("上一次 AI 任务还在进行中，请稍候。")
    }

    /** 装载当前选中章节：自动填充提示词（同章保留用户已编辑的提示词）+ 摘要（有未保存修改时不覆盖）。 */
    private fun loadSelected() {
        val n = curChapter() ?: return
        if (loadedPromptKey != n) {
            loadedPromptKey = n
            etPrompt.setText(Repo.summaryPrompt(n))
        }
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
