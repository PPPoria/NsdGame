package com.ppporia.nsdgames.ui.azul

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnLayout
import com.google.android.material.color.MaterialColors
import com.poria.base.R as BaseR
import com.poria.base.ui.BaseActivity
import com.poria.game.azul.AzulPhase
import com.poria.game.azul.AzulPlate
import com.poria.game.azul.AzulSnapshot
import com.poria.game.azul.AzulWorkspace
import com.poria.game.azul.Tile
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.ActivityAzulGameBinding
import com.ppporia.nsdgames.viewmodel.azul.AzulGameUiState
import com.ppporia.nsdgames.viewmodel.azul.AzulGameVM
import com.ppporia.nsdgames.viewmodel.azul.AzulPlayerUi
import java.io.File

/**
 * 花砖对局页（竖屏）。
 *
 * ```
 * ┌──────────────────────────────────────┐
 * │ [阿乐]  [老王]  [Mia]   ← 横向等分，每人一块 AzulPlayerLayout（compact）
 * ├──────────────────────────────────────┤
 * │    ◎    ◎    ◎    ← AzulPlatesLayout（瀑布布局，盘里 2×2）
 * │    ◎    ◎    ◎
 * │    ◎    ◎    ◎
 * │  ▪ ▪ ▪ ▪ ▪        −5  ← AzulRedundantLayout（一排砖 / 5 个统计块 + 先手砖）
 * ├──────────────────────────────────────┤
 * │ 小明                          27 分  │ ← AzulPlayerLayout（整屏宽）
 * │ ┌─────────┬─▶─┬─────────┐            │
 * │ │ 预备区  │ ▶ │  墙面   │            │
 * │ ├─────────┴───┴─────────┤            │
 * │ │ ▪▪▪▪▪▪▪▪▪▪▪…    −3     │            │
 * │ └───────────────────────┘            │
 * └──────────────────────────────────────┘
 * ```
 *
 * **交互是"两步选砖"**：
 * 1. 点一块砖（盘子里的、中央区的）→ 它亮起来，我的盘上"能放的行"高亮、其余压暗；
 * 2. 点其中一个高亮的行 → 立刻把命令发出去。
 *
 * 再点一次同一个来源 = 取消。规则判定一行都不在这层写：**能不能放**只是给的高亮提示，
 * 真校验在服务器的 `AzulEngine`；被拒了就把 `state.note` 弹出来。
 *
 * **颜色只有一张表**（[tilePaints] / [faintPaints] / [emptyPaint]）：各个自定义 View 不认识
 * Tile、也不认识任何具体颜色，只接收 [AzulTilePaint]。
 */
class AzulGameActivity : BaseActivity<ActivityAzulGameBinding>() {

    private val vm by viewModels<AzulGameVM>()

    /** 头像解码缓存：同一个文件只解一次（和房间页同一套写法）。 */
    private val avatarBitmaps = HashMap<String, Bitmap>()

    /** 砖的颜色表：**全对局页唯一一份**。 */
    private lateinit var tilePaints: Map<Tile, AzulTilePaint>

    /** paint → Tile 的反查（点选回调是 paint 维度，发命令要 Tile）。 */
    private lateinit var tileOfPaint: Map<AzulTilePaint, Tile>

    /** 墙面空位的淡色版本：同一套颜色往背景上混，深浅主题都成立。 */
    private lateinit var faintPaints: Map<Tile, AzulTilePaint>

    /** 空位（预备区空格、惩罚区空格）的 paint。 */
    private lateinit var emptyPaint: AzulTilePaint

    /** 五种颜色的固定顺序（统计形态按这个顺序列，位置才不会跳）。 */
    private val palette: List<AzulTilePaint> get() = AzulTiles.colors.map(::paintOf)

    /** 选中了哪一坨砖：`plate = null` 表示中央区。 */
    private var selection: Selection? = null

    private var lastSeq = -1
    private var lastNote: String? = null
    private var lastUi: AzulGameUiState? = null

    override fun onCreateBinding(): ActivityAzulGameBinding =
        ActivityAzulGameBinding.inflate(layoutInflater)

    override fun initView() {
        buildPaints()

        // 公共区那一排砖的高度 = 我的砖的实测边长：这是"场上砖和我的砖一样大"的落点
        binding.azulMe.workspace.doOnLayout {
            val tile = binding.azulMe.workspace.tileSize
            if (tile > 0) binding.azulRedundant.tileSize = tile
        }

        // 第一步：点盘子里的砖 / 中央区的砖
        binding.azulPlates.onTileClick = { plateIndex, paint ->
            val tile = tileOfPaint[paint]
            val plate = currentPlates()?.getOrNull(plateIndex)
            if (tile != null && plate != null) select(Selection(plate.tiles, tile))
        }
        binding.azulRedundant.onTileClick = { paint ->
            tileOfPaint[paint]?.let { tile -> select(Selection(null, tile)) }
        }
        // 第二步：点预备区某一行
        binding.azulMe.workspace.onFloorRowClick = { row -> commit(row) }
    }

    override suspend fun bindUi() {
        vm.state.collect { render(it) }
    }

    // ── 颜色表 ──────────────────────────────────────────────────────────────

    /**
     * 砖 → (颜色, 花纹, 小字)。现在没有纹理素材，bitmap 一律 null（纯色块）。
     *
     * 空位淡色 = 颜色按 [FAINT_RATIO] 往当前主题的背景上混：墙面要让人一眼看出
     * "这一格能放什么颜色"，但又不能跟已经铺好的砖抢注意力。
     */
    private fun buildPaints() {
        val colors = mapOf(
            Tile.Blue to ContextCompat.getColor(this, R.color.azul_tile_blue),
            Tile.Yellow to ContextCompat.getColor(this, R.color.azul_tile_yellow),
            Tile.Red to ContextCompat.getColor(this, R.color.azul_tile_red),
            Tile.Black to ContextCompat.getColor(this, R.color.azul_tile_black),
            Tile.White to ContextCompat.getColor(this, R.color.azul_tile_white),
            Tile.FirstAct to ContextCompat.getColor(this, R.color.azul_tile_first),
        )
        val surface = MaterialColors.getColor(binding.root, android.R.attr.colorBackground)
        val firstActLabel = getString(R.string.azul_first_act)

        tilePaints = colors.mapValues { (tile, color) ->
            AzulTilePaint(
                color = color,
                // 先手砖上的「先」字也归这张表：容器只管把 label 写到砖上
                label = if (tile == Tile.FirstAct) firstActLabel else null,
            )
        }
        tileOfPaint = tilePaints.entries.associate { (tile, paint) -> paint to tile }
        faintPaints = colors.mapValues { (_, color) ->
            AzulTilePaint(ColorUtils.blendARGB(color, surface, FAINT_RATIO))
        }
        emptyPaint = AzulTilePaint(ContextCompat.getColor(this, R.color.azul_slot))
    }

    private fun paintOf(tile: Tile): AzulTilePaint =
        tilePaints[tile] ?: AzulTilePaint(ColorUtils.setAlphaComponent(0x000000, 0x22))

    private fun faintOf(tile: Tile): AzulTilePaint = faintPaints[tile] ?: paintOf(tile)

    // ── 渲染 ────────────────────────────────────────────────────────────────

    private fun render(ui: AzulGameUiState) {
        val snapshot = ui.snapshot
        val seq = snapshot?.seq ?: -1
        if (seq != lastSeq) {
            // 局面变了：之前那一步的选中作废（那一摞砖可能已经不在了）
            lastSeq = seq
            selection = null
            lastNote = null
        }
        lastUi = ui

        val desktop = snapshot?.desktop
        val me = ui.players.firstOrNull { it.mine }
        val others = ui.players.filterNot { it.mine }

        ensureOtherPlayers(others.size)
        others.forEachIndexed { index, player ->
            bindPlayer(
                binding.azulOthers.getChildAt(index) as AzulPlayerLayout,
                player,
                compact = true,
            )
        }
        bindPlayer(binding.azulMe, me, compact = false)

        binding.azulPlates.bind(desktop?.plates?.map { plate -> plate.tiles.map(::paintOf) }.orEmpty())
        binding.azulRedundant.bind(
            tiles = desktop?.redundant?.map(::paintOf).orEmpty(),
            palette = palette,
            // 先手砖还在桌上 = 本轮还没人从中央拿过砖。引擎把标记记在"拿了中央的那个人"的
            // 惩罚区里，所以这里反着查一遍，不用给 :game-core 加新 API。
            firstAct = if (desktop != null && desktop.workspaces.values.none {
                    it.punishment.tiles.contains(Tile.FirstAct)
                }
            ) paintOf(Tile.FirstAct) else null,
        )

        val tile = binding.azulMe.workspace.tileSize
        if (tile > 0) binding.azulRedundant.tileSize = tile

        applyInteraction()
        showNoteOnce(ui.note)
    }

    private fun bindPlayer(layout: AzulPlayerLayout, player: AzulPlayerUi?, compact: Boolean) {
        layout.bind(
            name = player?.name ?: AzulGameVM.UNKNOWN_NAME,
            score = player?.score ?: 0,
            avatar = player?.avatar?.let(::avatarOf),
            act = player?.act == true,
            compact = compact,
        )
        val workspace = player?.workspace
        layout.bindWorkspace(
            floor = workspace?.floor?.rows
                ?.map { row -> row?.let { (tile, n) -> paintOf(tile) to n } }
                .orEmpty(),
            wall = wallPaints(workspace?.wall?.grid),
            punishment = workspace?.punishment?.tiles?.map(::paintOf).orEmpty(),
            empty = emptyPaint,
        )
    }

    /** 墙面：已铺的用实色，空位用该位置"应该出现的颜色"的淡色。 */
    private fun wallPaints(grid: List<List<Tile?>>?): List<List<AzulTilePaint>> =
        List(5) { row ->
            List(5) { col ->
                val tile = grid?.getOrNull(row)?.getOrNull(col)
                if (tile != null) paintOf(tile) else faintOf(AzulTiles.tileAt(row, col))
            }
        }

    private fun avatarOf(file: File): Bitmap? {
        avatarBitmaps[file.path]?.let { return it }
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null
        avatarBitmaps[file.path] = bitmap
        return bitmap
    }

    // ── 两步选砖 ────────────────────────────────────────────────────────────

    private data class Selection(
        /** 盘子上的砖（发命令时要整份，服务器靠它认盘子）；null = 中央区。 */
        val plate: List<Tile>?,
        val tile: Tile,
    )

    private fun currentPlates(): List<AzulPlate>? =
        lastUi?.snapshot?.desktop?.plates

    /** 第一步：选中一坨砖；再点同一个位置 = 取消。 */
    private fun select(selection: Selection) {
        val ui = lastUi ?: return
        if (!ui.myTurn) {
            toast(getString(R.string.azul_not_my_turn))
            return
        }
        // 一轮打完到下一轮开始之间（引擎那 1.5 秒）谁都不能拿砖，点了也只会被服务器拒
        if (ui.phase != AzulPhase.RoundPlaying) {
            toast(getString(R.string.azul_settling))
            return
        }
        this.selection = if (this.selection == selection) null else selection
        applyInteraction()
    }

    /** 第二步：点了某一行。只认高亮着的行，其余点了个寂寞。 */
    private fun commit(row: Int) {
        val ui = lastUi ?: return
        val selected = selection ?: return
        if (row !in targetsOf(ui, selected.tile)) return

        selection = null
        applyInteraction()
        val plate = selected.plate
        if (plate != null) {
            vm.takeFromPlate(row, selected.tile, plate)
        } else {
            vm.takeFromRedundant(row, selected.tile)
        }
    }

    /** 把选中态、可选行、来源高亮同步到界面上。 */
    private fun applyInteraction() {
        val ui = lastUi
        val selected = selection
        // 只有"该我动 + 已经选好来源"时才有可选行
        val acting: AzulGameUiState? = ui?.takeIf { it.myTurn }

        val targets = if (selected != null && acting != null) {
            targetsOf(acting, selected.tile)
        } else emptyList()
        binding.azulMe.workspace.setRowTargets(targets)

        val plates = ui?.snapshot?.desktop?.plates
        val plateIndex = if (selected?.plate != null && plates != null) {
            plates.indexOfFirst { it.tiles == selected.plate }
        } else -1
        val paint = selected?.let { paintOf(it.tile) }
        binding.azulPlates.setSelected(if (plateIndex >= 0) plateIndex else null, paint)
        binding.azulRedundant.setSelected(if (selected != null && plateIndex < 0) paint else null)
    }

    /**
     * 这一色能放进哪几行：**只是给玩家的高亮提示**，真判定在引擎。
     *
     * 条件：这一行是空的或者同色、还没填满、而且墙面上这一行还没铺过这个颜色
     * （引擎的 `AzulWall.add` 会拒绝同一行出现重复颜色）。
     *
     * 一行都不合规时的退路（否则玩家会"点不动"）：挑**已经装着别的颜色的行**——
     * 引擎对这种行的行为是把进来的砖全丢进惩罚区（`AzulFloor.add` 里 `tile != oriTile` 那一支），
     * 行本身不动、墙面也不会被写进重复颜色，是唯一"安全丢弃"的方式。
     * 连这种行都没有（五行全空/全同色但墙面都堵死）时才退到全部五行——那种角落情况下
     * 更该由引擎补一条"直接丢地板"的命令，UI 只能先让人能动。
     */
    private fun targetsOf(ui: AzulGameUiState, tile: Tile): List<Int> {
        val snapshot: AzulSnapshot = ui.snapshot ?: return emptyList()
        val workspace: AzulWorkspace = snapshot.desktop.workspaces[ui.myId] ?: return emptyList()
        val rows = workspace.floor.rows
        val grid = workspace.wall.grid

        val legal = (0..4).filter { row ->
            val line = rows.getOrNull(row)
            val length = row + 1
            val quantity = line?.second ?: 0
            val sameColor = line?.first == null || line.first == tile
            val hasRoom = quantity < length
            val col = AzulTiles.columnOf(tile, row)
            val wallFree = col >= 0 && grid.getOrNull(row)?.getOrNull(col) == null
            sameColor && hasRoom && wallFree
        }
        if (legal.isNotEmpty()) return legal

        val dumpable = (0..4).filter { row ->
            val line = rows.getOrNull(row)
            line?.first != null && line.first != tile
        }
        return dumpable.ifEmpty { (0..4).toList() }
    }

    private fun showNoteOnce(note: String?) {
        if (note.isNullOrEmpty() || note == lastNote) return
        lastNote = note
        toast(note)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ── 其他玩家 ────────────────────────────────────────────────────────────

    /**
     * 其他玩家等分屏宽（不滑动）：`width = 0dp, weight = 1`。
     * 工作区的砖大小是从宽度推出来的，所以"人多棋盘就小"是自动的。
     */
    private fun ensureOtherPlayers(count: Int) {
        val container = binding.azulOthers
        while (container.childCount > count) {
            container.removeViewAt(container.childCount - 1)
        }
        val gap = resources.getDimensionPixelSize(BaseR.dimen.space_s)
        while (container.childCount < count) {
            container.addView(
                AzulPlayerLayout(this),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        for (index in 0 until container.childCount) {
            val params = container.getChildAt(index).layoutParams as LinearLayout.LayoutParams
            params.marginEnd = if (index == container.childCount - 1) 0 else gap
            container.getChildAt(index).layoutParams = params
        }
    }

    private companion object {
        /** 空位淡色往背景上混多少（越大越淡）。0.55 在深浅主题下都还看得出颜色。 */
        private const val FAINT_RATIO = 0.55f
    }
}
