package com.example.novelstudio

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

/**
 * 单 Activity + 8 个常驻 Fragment（TabLayout 切换，hide/show 不销毁视图，
 * 保证长任务（如范文拆解）切页不中断、流式输出不丢）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tabs: TabLayout
    private lateinit var statusBar: TextView
    private val container: FrameLayout by lazy { findViewById(R.id.content_frame) }

    private val fragments = mutableListOf<Fragment>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.initRoot(applicationContext)
        setContentView(R.layout.activity_main)
        maybeAskStorageAccess()

        tabs = findViewById(R.id.tab_layout)
        statusBar = findViewById(R.id.status_bar)

        // targetSdk 35 默认 edge-to-edge：adjustResize 失效，需手动处理 insets——
        // ① 顶部标签页按系统栏+挖孔留安全区；② 软键盘(IME)弹起时给根布局加等高底边距，
        //    让整体界面上移，避免输入法界面遮挡编辑内容。
        val root = findViewById<ViewGroup>(R.id.root_layout)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val safe = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val imeBottom = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, imeBottom))
            val nav = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            statusBar.setPadding(statusBar.paddingLeft, statusBar.paddingTop,
                statusBar.paddingRight, nav + (4 * resources.displayMetrics.density).toInt())
            insets
        }

        val titles = listOf(
            getString(R.string.tab_settings),
            getString(R.string.tab_style),
            getString(R.string.tab_deconstruct),
            getString(R.string.tab_characters),
            getString(R.string.tab_relationships),
            getString(R.string.tab_outline),
            getString(R.string.tab_summary),
            getString(R.string.tab_draft))

        titles.forEach { tabs.addTab(tabs.newTab().setText(it)) }

        if (savedInstanceState == null) {
            val fm = supportFragmentManager
            val tr = fm.beginTransaction()
            val classes = listOf(
                SettingsFragment(), StyleFragment(), DeconstructFragment(),
                CharactersFragment(), RelationshipsFragment(), OutlineFragment(),
                SummaryFragment(), DraftFragment())
            classes.forEachIndexed { i, f ->
                fragments.add(f)
                tr.add(container.id, f, "f$i")
                if (i != 0) tr.hide(f)
            }
            tr.commitAllowingStateLoss()
        } else {
            for (i in 0 until 8) {
                supportFragmentManager.findFragmentByTag("f$i")?.let { fragments.add(it) }
            }
        }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val idx = tab.position
                Store.setActiveTab(idx)
                val tr = supportFragmentManager.beginTransaction()
                fragments.forEachIndexed { i, f ->
                    if (i == idx) tr.show(f) else tr.hide(f)
                }
                tr.commitAllowingStateLoss()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Store.status.collect { statusBar.text = it }
                }
                launch {
                    Store.busy.collect { busy ->
                        statusBar.setTextColor(if (busy) Color.parseColor("#1565C0") else Color.BLACK)
                    }
                }
            }
        }
    }

    /** 引导授予「所有文件访问」，以便把项目文件放到公共 Download/NovelStudio 下。 */
    private fun maybeAskStorageAccess() {
        if (Repo.hasAllFilesAccess(this)) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("文件存储权限")
            .setMessage(
                "为了把项目文件保存在「内部存储/Download/NovelStudio/」（方便用文件管理器和电脑直接查看、编辑），" +
                        "需要授予本应用「所有文件访问」权限。\n\n" +
                        "点击「去授权」后，请在列表中找到本应用并打开开关，然后返回。\n" +
                        "不授权也可正常使用，但项目会存放在应用专属目录（Android/data/ 下，不便于直接操作）。")
            .setPositiveButton("去授权") { _, _ -> Repo.openAllFilesSettings(this) }
            .setNegativeButton("暂不，用应用目录", null)
            .setCancelable(true)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 从授权页返回：若刚获得授权，根目录会从应用专属目录切到 Download，通知各页刷新
        val oldRoot = Store.rootDir?.absolutePath
        val root = Repo.initRoot(applicationContext)
        if (oldRoot != null && root.absolutePath != oldRoot) Changes.bump()
    }
}
