package com.example.novelstudio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SettingsFragment : Fragment() {

    private lateinit var spinProjects: Spinner
    private lateinit var lblProject: TextView
    private lateinit var etBaseUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etTemp: EditText
    private lateinit var etMaxtok: EditText
    private lateinit var txtHelp: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_settings, container, false)
        spinProjects = v.findViewById(R.id.spin_projects)
        lblProject = v.findViewById(R.id.lbl_project)
        etBaseUrl = v.findViewById(R.id.et_base_url)
        etApiKey = v.findViewById(R.id.et_api_key)
        etModel = v.findViewById(R.id.et_model)
        etTemp = v.findViewById(R.id.et_temp)
        etMaxtok = v.findViewById(R.id.et_maxtok)
        txtHelp = v.findViewById(R.id.txt_help)

        txtHelp.text = """
            【使用流程】（与顶部标签页编号一致）

            1. 设置与项目：填好 API 地址与模型名，点「测试连接」；然后「新建项目」。
            2. 范文提炼文风：导入范文 txt，AI 自动提炼文风特征，可人工修改保存。
            2.5 范文拆解仿写（偷懒通道）：选一篇范文让 AI 分段通读，自动拆成完整项目——文风、人物、关系、大纲、每章摘要、原文按章切分存为正文。可填仿写设定（换人名/背景）改写实例，再到第 7 页逐章「AI 生成本章正文」即得仿写小说。
            3. 人物档案：逐个添加出场人物，自动保存为 characters.json。
            4. 人物关系：填写人物关系（明面关系与隐藏面、双方称谓）。
            5. 剧情大纲：逐章填写简版大纲（时间/人物/地点/事件/看点/钩子）。
            6. 章节摘要：AI 联合文风+人物+关系+大纲，扩写出每章摘要；逐章检查修订后保存。
            7. 章节正文：AI 参考文风+人物+本章摘要+上一章结尾，逐章流式生成正文；修订后保存，最后一键导出全文。

            【文件保存在】授予「所有文件访问」权限后存于 内部存储/Download/NovelStudio/projects/<项目名>/，
            全部为可读的 md/json 文本，文件管理器和电脑都能直接操作；未授权时存于应用专属目录。
            首次启动已尝试引导授权；如被拒绝，可在系统设置 → 应用 → 小说连载工坊 → 权限中重新开启。

            【提示】生成较慢属正常（本地推理），底部状态栏会显示进度；生成期间请勿重复点击。
        """.trimIndent()

        v.findViewById<View>(R.id.btn_new_project).setOnClickListener { newProject() }
        v.findViewById<View>(R.id.btn_open_project).setOnClickListener { openProject() }
        v.findViewById<View>(R.id.btn_test_conn).setOnClickListener { testConnection() }
        v.findViewById<View>(R.id.btn_save_cfg).setOnClickListener { saveConfig() }

        refresh()
        return v
    }

    fun refresh() {
        val projects = Repo.listProjects()
        spinProjects.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, projects)
        lblProject.text = Store.projectDir?.absolutePath ?: "（未打开）"
        lblProject.setTextColor(
            if (Store.projectDir != null) 0xFF2E7D32.toInt() else 0xFFB00020.toInt())
        etBaseUrl.setText(Store.cfg.baseUrl)
        etApiKey.setText(Store.cfg.apiKey)
        etModel.setText(Store.cfg.model)
        etTemp.setText(Store.cfg.temperature)
        etMaxtok.setText(Store.cfg.maxTokens)
    }

    private fun collectCfg() {
        Store.cfg.baseUrl = etBaseUrl.text.toString().trim()
        Store.cfg.apiKey = etApiKey.text.toString().trim()
        Store.cfg.model = etModel.text.toString().trim()
        Store.cfg.temperature = etTemp.text.toString().trim().ifBlank { "0.8" }
        Store.cfg.maxTokens = etMaxtok.text.toString().trim().ifBlank { "8192" }
    }

    private fun saveConfig() {
        if (Store.projectDir == null) {
            toast("请先新建或打开项目。")
            return
        }
        collectCfg()
        Repo.saveConfig()
    }

    private fun openProject() {
        val projects = Repo.listProjects()
        if (projects.isEmpty()) {
            toast("暂无项目，请先「新建项目」。")
            return
        }
        val sel = spinProjects.selectedItemPosition.coerceAtLeast(0)
        if (sel >= projects.size) return
        doOpen(File(Store.rootDir, projects[sel]))
    }

    private fun doOpen(dir: File) {
        collectCfg()
        Repo.openProject(dir)
        refresh()
        Changes.bump()
        Toast.makeText(requireContext(), "项目已打开：" + dir.name, Toast.LENGTH_SHORT).show()
    }

    private fun newProject() {
        val input = EditText(requireContext())
        input.hint = "书名 / 系列名"
        AlertDialog.Builder(requireContext())
            .setTitle("新建项目")
            .setMessage("请输入项目名（书名/系列名）：")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val dir = File(Store.rootDir, name)
                if (dir.exists()) {
                    Toast.makeText(requireContext(), "项目已存在，直接打开。", Toast.LENGTH_SHORT).show()
                }
                doOpen(dir)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testConnection() {
        collectCfg()
        if (Store.cfg.baseUrl.isBlank() || Store.cfg.model.isBlank()) {
            toast("请先填写 API 地址与模型名称。")
            return
        }
        val ok = Jobs.run("测试连接") {
            val cfg = Store.cfg.copy()
            LlmClient.call(
                cfg,
                listOf(LlmClient.ChatMessage("user", "请只回复两个字：连接成功")))
            withContext(Dispatchers.Main) {
                if (isAdded) Toast.makeText(
                    requireContext(), "连接成功！模型 ${cfg.model} 可用。", Toast.LENGTH_SHORT).show()
            }
        }
        if (!ok) toast("上一次 AI 任务还在进行中，请稍候。")
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
