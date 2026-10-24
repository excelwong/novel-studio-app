package com.example.novelstudio

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class StyleFragment : Fragment() {

    private lateinit var adapter: SimpleListAdapter
    private lateinit var etStyle: EditText
    private var selectedSample: File? = null

    private val pickSamples =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            val dir = Repo.projectFile("samples") ?: return@registerForActivityResult
            var n = 0
            for (u in uris) {
                if (importUri(u, dir)) n++
            }
            Toast.makeText(requireContext(), "已导入 $n 篇范文。", Toast.LENGTH_SHORT).show()
            refreshSamples()
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_style, container, false)
        val rv = v.findViewById<RecyclerView>(R.id.rv_samples)
        adapter = SimpleListAdapter { pos ->
            selectedSample = Repo.sampleFiles().getOrNull(pos)
        }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        etStyle = v.findViewById(R.id.et_style)
        v.findViewById<Button>(R.id.btn_add_sample).setOnClickListener {
            if (ensureProject()) pickSamples.launch(arrayOf("text/*", "text/plain"))
        }
        v.findViewById<Button>(R.id.btn_del_sample).setOnClickListener { delSample() }
        v.findViewById<Button>(R.id.btn_extract_style).setOnClickListener { extractStyle() }
        v.findViewById<Button>(R.id.btn_save_style).setOnClickListener {
            if (ensureProject()) {
                Repo.writeText(Repo.projectFile("style")!!, etStyle.text.toString())
                Store.setStatus("文风特征已保存。")
            }
        }

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Changes.version.collect { if (isAdded) refresh() }
            }
        }
        refresh()
        return v
    }

    fun refresh() {
        refreshSamples()
        etStyle.setText(Repo.readText(Repo.projectFile("style")))
    }

    private fun refreshSamples() {
        adapter.set(Repo.sampleFiles().map { it.name })
        selectedSample = null
    }

    private fun importUri(uri: Uri, dir: File): Boolean {
        return try {
            val name = queryName(uri) ?: "sample_${System.currentTimeMillis()}.txt"
            requireContext().contentResolver.openInputStream(uri)?.use { ins ->
                val text = ins.readBytes().toString(Charsets.UTF_8)
                Repo.writeText(File(dir, name), text)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun queryName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')
        return name
    }

    private fun delSample() {
        val f = selectedSample ?: run { toast("请先在列表中选择范文。"); return }
        AlertDialog.Builder(requireContext())
            .setTitle("确认")
            .setMessage("删除范文《${f.name}》？（只从项目移除）")
            .setPositiveButton("删除") { _, _ ->
                f.delete()
                refreshSamples()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun extractStyle() {
        if (!ensureProject()) return
        val files = Repo.sampleFiles()
        if (files.isEmpty()) { toast("请先添加范文 txt。"); return }
        val ok = Jobs.run("提炼文风特征") {
            val parts = files.map { "《${it.name}》\n${Repo.clip(Repo.readText(it), 8000)}" }
            val system = "你是一位资深小说编辑，擅长从范文中提炼可模仿的写作特征。"
            val user = ("请阅读以下范文，提炼『文风特征说明』，供 AI 后续模仿写作使用。"
                    + "必须涵盖：1 叙事视角与人称；2 句式与语言风格；3 对话风格与占比；"
                    + "4 节奏与场景切换；5 人物刻画手法；6 常用词汇与标志性表达；"
                    + "7 情感基调；8 写作时应避免之处。条目化中文输出，800 字以内。\n\n"
                    + parts.joinToString("\n\n====\n\n"))
            val content = LlmClient.call(
                Store.cfg.copy(),
                listOf(LlmClient.ChatMessage("system", system), LlmClient.ChatMessage("user", user)))
            Repo.writeText(Repo.projectFile("style")!!, content.trim())
            withContext(Dispatchers.Main) {
                if (isAdded) etStyle.setText(content.trim())
            }
        }
        if (!ok) toast("上一次 AI 任务还在进行中，请稍候。")
    }

    private fun ensureProject(): Boolean {
        if (Store.projectDir == null) {
            toast("请先在「1.设置与项目」新建或打开项目。")
            return false
        }
        return true
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
