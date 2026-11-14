package com.wcm.checkin

import com.wcm.checkin.databinding.ActivityRemotePreviewBinding
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 远程大图预览（v0.9.17）：浏览博主页点小图后进来，看的是 CDN 上的大图。
 *
 * 与本地图库的 [ImagePreviewActivity] 的分工：那个读 SAF 里的文件（有分享 / 删除 /
 * 用系统应用打开），这个只读远程 URL，所以只有「翻页 + 缩放」两件事 ——
 * 不落盘、不提供分享，也就没有额外权限与临时文件。
 *
 * 关键取舍：
 * - **尺寸段**：列表里的小图是 `orj360`（最长边 360），直接放大是一张糊图。
 *   这里统一用 [WeiboImageUrl.fullFromThumb] 换到 `large` 段（原分辨率）；
 *   换不出来（非 sinaimg 地址）才退回缩略图地址。
 * - **并发闸门**：`large` 单张可达 2MB+，解出来 12-16MB。快速连滑会同时解出十几张，
 *   低端机直接 OOM —— 用 [Semaphore] 限 2 路（当前页 + 预取下一页）。
 * - **取消**：页被回收立刻取消未完成的解码，否则滑过去还在后台解。
 * - 系统栏图标恒为浅色（纯黑背景），不用 auto（浅色模式下会切成看不见的深色图标）。
 */
class RemotePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemotePreviewBinding

    private lateinit var pager: ViewPager2
    private lateinit var tvIndex: TextView
    private lateinit var loading: ProgressBar

    private val urls = ArrayList<String>()
    private val adapter = PagerAdapter()

    /** 已经出过结果（成功或失败）的页：只影响进度圈，不影响内容。 */
    private val settled = HashSet<Int>()

    /** 失败提示只弹一次：连着翻几张都失败时不该刷一串 toast。 */
    private var failToasted = false

    private val fullLoadSlot = Semaphore(2)

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            renderIndex()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        binding = ActivityRemotePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pager = binding.remotePreviewPager
        tvIndex = binding.tvRemotePreviewIndex
        loading = binding.remotePreviewLoading

        savedInstanceState?.getStringArrayList(KEY_URLS)?.let { urls.addAll(it) }
        if (urls.isEmpty()) {
            intent.getStringArrayListExtra(EXTRA_URLS)?.let { urls.addAll(it) }
        }
        if (urls.isEmpty()) {
            finish()
            return
        }
        var startIndex = intent.getIntExtra(EXTRA_INDEX, 0)
        savedInstanceState?.let { startIndex = it.getInt(KEY_INDEX, startIndex) }

        binding.remotePreviewRoot.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
                // 左右也要让位：横屏时挖孔/手势区在侧边
                root.updatePadding(
                    top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right,
                )
                WindowInsetsCompat.CONSUMED
            }
        }

        pager.adapter = adapter
        pager.registerOnPageChangeCallback(pageCallback)
        pager.setCurrentItem(startIndex.coerceIn(0, urls.size - 1), false)
        renderIndex()
        // 索引快照读完后刷一次：进来时可能还没读完，那时先不标「已抓取」
        CrawlMarks.ensure(this) {
            if (!isFinishing && !isDestroyed) renderIndex()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_URLS, ArrayList(urls))
        outState.putInt(KEY_INDEX, pager.currentItem)
    }

    override fun onDestroy() {
        pager.unregisterOnPageChangeCallback(pageCallback)
        super.onDestroy()
    }

    private fun renderIndex() {
        val pos = pager.currentItem
        if (pos !in urls.indices) return
        // 这张抓过没有：计数后面跟一个「已抓取」，不额外占一块界面（v0.9.18）
        val done = CrawlMarks.hasPic(WeiboImageUrl.pidFromImageUrl(urls[pos]))
        tvIndex.text = if (done) {
            getString(R.string.preview_index_done_fmt, pos + 1, urls.size)
        } else {
            getString(R.string.preview_index_fmt, pos + 1, urls.size)
        }
        loading.visibility = if (pos in settled) View.GONE else View.VISIBLE
    }

    private inner class PagerAdapter : RecyclerView.Adapter<PagerAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = ZoomableImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                contentDescription = getString(R.string.preview_remote_desc)
            }
            return Holder(view)
        }

        override fun getItemCount(): Int = urls.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(urls[position])
        }

        override fun onViewRecycled(holder: Holder) {
            super.onViewRecycled(holder)
            holder.cancel()
        }

        inner class Holder(val zoomView: ZoomableImageView) : RecyclerView.ViewHolder(zoomView) {

            /** 当前这张大图的解码任务；换绑/回收时必须取消。 */
            private var job: Job? = null

            fun bind(url: String) {
                // 放大态停用翻页手势，回到 1x 恢复
                zoomView.onZoomChanged = { zoomed -> pager.isUserInputEnabled = !zoomed }
                zoomView.setImageDrawable(null)
                zoomView.tag = url
                job?.cancel()
                job = lifecycleScope.launch(Dispatchers.IO) {
                    val big = WeiboImageUrl.fullFromThumb(url).ifEmpty { url }
                    val bmp = try {
                        fullLoadSlot.withPermit { RemoteThumb.loadFull(big) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        // 换绑 / 回收之后结果作废：tag 不再是这个 URL 就别往这张图上盖
                        if (zoomView.tag != url) return@withContext
                        val at = bindingAdapterPosition
                        if (at != RecyclerView.NO_POSITION) settled.add(at)
                        if (pager.currentItem == at) loading.visibility = View.GONE
                        if (bmp != null) {
                            // 刻意 BitmapDrawable(null, ...)：density=NONE 才不会被按屏幕密度
                            // 缩放，ZoomableImageView 的缩放/位移坐标映射才不漂移
                            @Suppress("UseKtx")
                            zoomView.setImageDrawable(BitmapDrawable(null, bmp))
                        } else if (!failToasted) {
                            failToasted = true
                            Toast.makeText(
                                applicationContext, R.string.preview_remote_fail, Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }

            fun cancel() {
                job?.cancel()
                job = null
            }
        }
    }

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_INDEX = "index"
        private const val KEY_URLS = "remote_preview_urls"
        private const val KEY_INDEX = "remote_preview_index"

        /** 单帖最多看 18 张（与选图页的网格上限一致），再多就不是「看一眼」了。 */
        private const val MAX_PAGES = 18

        /** [urls] 是小图地址（orj360 等），本页自己换到大图段再加载。 */
        fun start(ctx: Context, urls: List<String>, index: Int) {
            if (urls.isEmpty()) return
            val safe = ArrayList(urls.take(MAX_PAGES))
            ctx.startActivity(
                Intent(ctx, RemotePreviewActivity::class.java)
                    .putStringArrayListExtra(EXTRA_URLS, safe)
                    .putExtra(EXTRA_INDEX, index.coerceIn(0, safe.size - 1)),
            )
        }
    }
}
