package top.teamaos.pdfreader.ui

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.teamaos.pdfreader.R
import top.teamaos.pdfreader.data.DocumentRecord
import top.teamaos.pdfreader.data.ThumbnailStore
import top.teamaos.pdfreader.databinding.ItemDocumentBinding

/** Recent and favourite documents, with a colour-coded type badge and a reading-progress bar. */
class DocumentAdapter(
    private val scope: CoroutineScope,
    private val onOpen: (DocumentRecord) -> Unit,
    private val onToggleFavourite: (DocumentRecord) -> Unit,
    private val onShare: (DocumentRecord) -> Unit,
    private val onRemove: (DocumentRecord) -> Unit,
) : ListAdapter<DocumentRecord, DocumentAdapter.Holder>(DIFF) {

    class Holder(val binding: ItemDocumentBinding) : RecyclerView.ViewHolder(binding.root) {
        /** Which document this recycled row is currently showing, so late covers land correctly. */
        var coverKey: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemDocumentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val record = getItem(position)
        val context = holder.itemView.context
        val binding = holder.binding

        binding.title.text = record.displayName

        val extension = record.displayName.substringAfterLast('.', "").uppercase()
        binding.badgeText.text = extension.take(4).ifEmpty { "DOC" }
        binding.badgeBackground.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, colourFor(extension)))

        val parts = buildList {
            if (record.pageCount > 0) add(context.getString(R.string.pages_count, record.pageCount))
            if (record.sizeBytes > 0) add(Formatter.formatShortFileSize(context, record.sizeBytes))
            if (record.lastOpenedAt > 0) {
                add(
                    DateUtils.getRelativeTimeSpanString(
                        record.lastOpenedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                )
            } else {
                // Never opened: this is a search result, and where it is matters more than when.
                record.folder?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
        binding.subtitle.text = parts.joinToString("  ·  ")

        // Only worth showing once there is actual progress to show.
        val showProgress = record.pageCount > 1 && record.lastPage > 0
        binding.readProgress.visibility = if (showProgress) View.VISIBLE else View.GONE
        if (showProgress) {
            binding.readProgress.max = record.pageCount - 1
            binding.readProgress.setProgressCompat(record.lastPage, false)
        }

        bindCover(holder, record)

        binding.favouriteButton.setImageResource(
            if (record.isFavorite) R.drawable.ic_star else R.drawable.ic_star_border,
        )
        binding.favouriteButton.setOnClickListener { onToggleFavourite(record) }
        binding.overflowButton.setOnClickListener { view -> showOverflow(view, record) }
        holder.itemView.setOnClickListener { onOpen(record) }
    }

    /**
     * Show the document's own first page instead of a generic badge, once one has been rendered.
     *
     * Covers are produced when a document is opened and cached on disk, so nothing is rendered from
     * the list itself — the worst case here is a small JPEG decode. The row is tagged with the URI
     * so a recycled view cannot end up showing the previous document's cover.
     */
    private fun bindCover(holder: Holder, record: DocumentRecord) {
        val binding = holder.binding
        val store = ThumbnailStore.get(holder.itemView.context)
        val key = record.uri
        holder.coverKey = key

        val immediate = store.cached(key)
        if (immediate != null) {
            showCover(holder, immediate)
            return
        }
        showBadge(holder)
        scope.launch {
            val bitmap = store.load(key) ?: return@launch
            if (holder.coverKey == key) showCover(holder, bitmap)
        }
    }

    private fun showCover(holder: Holder, bitmap: Bitmap) {
        holder.binding.coverImage.setImageBitmap(bitmap)
        holder.binding.coverImage.visibility = View.VISIBLE
        holder.binding.badgeBackground.visibility = View.INVISIBLE
        holder.binding.badgeText.visibility = View.INVISIBLE
    }

    private fun showBadge(holder: Holder) {
        holder.binding.coverImage.visibility = View.GONE
        holder.binding.badgeBackground.visibility = View.VISIBLE
        holder.binding.badgeText.visibility = View.VISIBLE
    }

    private fun showOverflow(anchor: View, record: DocumentRecord) {
        PopupMenu(anchor.context, anchor).apply {
            menu.add(0, MENU_SHARE, 0, R.string.action_share)
            menu.add(0, MENU_REMOVE, 1, R.string.action_remove)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SHARE -> onShare(record)
                    MENU_REMOVE -> onRemove(record)
                }
                true
            }
            show()
        }
    }

    private fun colourFor(extension: String): Int = when (extension) {
        "PDF" -> R.color.type_pdf
        "DOC", "DOCX", "RTF", "ODT" -> R.color.type_doc
        "XLS", "XLSX", "CSV", "ODS" -> R.color.type_xls
        "PPT", "PPTX", "ODP" -> R.color.type_ppt
        else -> R.color.type_other
    }

    private companion object {
        const val MENU_SHARE = 1
        const val MENU_REMOVE = 2

        val DIFF = object : DiffUtil.ItemCallback<DocumentRecord>() {
            // By URI, not by id: a file found by searching storage has no row and so no id, and
            // comparing zeros would make every search result look like the same item.
            override fun areItemsTheSame(oldItem: DocumentRecord, newItem: DocumentRecord) =
                oldItem.uri == newItem.uri

            override fun areContentsTheSame(oldItem: DocumentRecord, newItem: DocumentRecord) =
                oldItem == newItem
        }
    }
}
